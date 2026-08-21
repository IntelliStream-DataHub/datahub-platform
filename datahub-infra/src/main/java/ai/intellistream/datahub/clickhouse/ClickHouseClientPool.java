// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantRemovedEvent;
import com.clickhouse.client.api.Client;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared pool of long-lived ClickHouse clients, one per tenant. The ClickHouse v2 client is
 * thread-safe and pools HTTP connections internally, so it is meant to be built once and reused —
 * building (and closing) a fresh client per insert/query churns sockets on the hot ingest path.
 *
 * <p>As a single Spring-managed singleton this pool is shared across every ClickHouse service
 * (datapoints, events, …), so each tenant has exactly one client process-wide rather than one per
 * service bean.
 *
 * <p>Keyed by tenant id ({@code organizationId}). Each entry also remembers the connection identity
 * it was built for, so two runtime cases are handled without a restart:
 * <ul>
 *   <li><b>Tenant added at runtime</b> — the client is built lazily on first use.</li>
 *   <li><b>Tenant config changed at runtime</b> (Vault refresh) — the next access sees a different
 *       connection identity and transparently rebuilds, closing the stale client.</li>
 * </ul>
 * {@link #invalidate(String)} force-evicts a tenant (e.g. on tenant removal); all clients are
 * closed on shutdown. Callers must NOT close a returned client — it is shared.
 */
@Component
@Slf4j
public class ClickHouseClientPool {

    private record Entry(String connKey, Client client) {}

    private final ConcurrentHashMap<String, Entry> clientsByTenant = new ConcurrentHashMap<>();

    public Client getClient(Tenant t) {
        String tenantId = t.getOrganizationId();
        String connKey = connKey(t);
        // Close any client we replace OUTSIDE the per-key compute lock to keep the lock hold short.
        AtomicReference<Client> stale = new AtomicReference<>();
        Entry entry = clientsByTenant.compute(tenantId, (id, existing) -> {
            if (existing != null && existing.connKey().equals(connKey)) {
                return existing;                  // still valid — reuse
            }
            if (existing != null) {
                stale.set(existing.client());     // config changed — rebuild, close old below
            }
            return new Entry(connKey, build(t));
        });
        closeQuietly(stale.get());
        return entry.client();
    }

    /**
     * Force-evict and close a tenant's cached client. Call when a tenant is removed or its
     * ClickHouse credentials are rotated; the next access rebuilds lazily.
     */
    public void invalidate(String tenantId) {
        Entry removed = clientsByTenant.remove(tenantId);
        if (removed != null) {
            closeQuietly(removed.client());
            log.info("Invalidated ClickHouse client for tenant {}", tenantId);
        }
    }

    /** Release a removed tenant's client when the tenant is dropped on a Vault refresh. */
    @EventListener
    public void onTenantRemoved(TenantRemovedEvent event) {
        invalidate(event.tenantId());
    }

    private static String connKey(Tenant t) {
        var ch = t.getClickHouseTenant();
        return ch.getHost() + "|" + ch.getDatabaseName() + "|" + ch.getUsername() + "|" + ch.getPassword();
    }

    private Client build(Tenant t) {
        var ch = t.getClickHouseTenant();
        return new Client.Builder()
                .addEndpoint("http://" + ch.getHost() + ":8123")
                .setUsername(ch.getUsername())
                .setPassword(ch.getPassword())
                .setDefaultDatabase(ch.getDatabaseName())
                .useHttpCompression(true)
                .compressClientRequest(true)
                .compressServerResponse(true)
                .serverSetting("max_threads", "8")
                .serverSetting("async_insert", "1")
                // The DateTime64(3) columns carry no timezone of their own, so without this the server
                // would parse and render them in whatever zone it happens to be configured with. Pin the
                // session to UTC so DateTime64 bind parameters (see ClickHouseService#toChDateTime) and
                // the values read back are both unambiguously UTC, on existing tenant tables included.
                .serverSetting("session_timezone", "UTC")
                .build();
    }

    @PreDestroy
    void closeAll() {
        clientsByTenant.values().forEach(e -> closeQuietly(e.client()));
        clientsByTenant.clear();
    }

    private void closeQuietly(Client c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception e) {
            log.warn("Failed to close ClickHouse client: {}", e.getMessage());
        }
    }
}
