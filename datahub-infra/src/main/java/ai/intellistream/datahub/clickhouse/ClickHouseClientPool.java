// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantRemovedEvent;
import com.clickhouse.client.api.Client;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
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
 *
 * <p><b>Two clients per tenant, not one.</b> {@link #getClient} hands back the owner's client — the
 * one that ingests and migrates. {@link #getReadOnlyClient} hands back one bound to the tenant's
 * {@code SELECT}-only user, which is what the caller-authored events filter language runs as. They
 * are separate entries because they are separate credentials; both are built lazily, so a tenant
 * that never takes a filter query never opens the second one.
 *
 * <p>{@link #invalidate(String)} force-evicts a tenant from both (e.g. on tenant removal); all
 * clients are closed on shutdown. Callers must NOT close a returned client — it is shared.
 */
@Component
@Slf4j
public class ClickHouseClientPool {

    private record Entry(String connKey, Client client) {}

    private final ConcurrentHashMap<String, Entry> ownerClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entry> readOnlyClients = new ConcurrentHashMap<>();

    /** Tenants already warned about having no read-only user, so the warning is one per tenant. */
    private final Set<String> readOnlyFallbackWarned = ConcurrentHashMap.newKeySet();

    /** The tenant's owner client: full rights on its database. Inserts and migrations go here. */
    public Client getClient(Tenant t) {
        var ch = t.getClickHouseTenant();
        return cached(ownerClients, t, ch.getUsername(), ch.getPassword());
    }

    /**
     * The tenant's {@code SELECT}-only client, for reads built from caller-authored input — the
     * events filter language above all.
     *
     * <p>A tenant provisioned before the tenant manager created a read-only user has no such
     * credentials, and falls back to the owner client rather than failing: refusing the query
     * would break every existing tenant's event filtering to gain a defence that only matters if
     * the renderer is already wrong. The fallback is warned about once per tenant, because it is a
     * state an operator wants to clear rather than one to live with.
     */
    public Client getReadOnlyClient(Tenant t) {
        var ch = t.getClickHouseTenant();
        if (!ch.hasReadOnlyUser()) {
            if (readOnlyFallbackWarned.add(t.getOrganizationId())) {
                log.warn("Tenant {} has no read-only ClickHouse user in its Vault config; event "
                                + "filter queries run as the owner '{}'. Re-provision the tenant to "
                                + "get the SELECT-only user.",
                        t.getOrganizationId(), ch.getUsername());
            }
            return getClient(t);
        }
        return cached(readOnlyClients, t, ch.getReadOnlyUsername(), ch.getReadOnlyPassword());
    }

    private Client cached(ConcurrentHashMap<String, Entry> clients, Tenant t,
                          String username, String password) {
        String tenantId = t.getOrganizationId();
        String connKey = connKey(t, username, password);
        // Close any client we replace OUTSIDE the per-key compute lock to keep the lock hold short.
        AtomicReference<Client> stale = new AtomicReference<>();
        Entry entry = clients.compute(tenantId, (id, existing) -> {
            if (existing != null && existing.connKey().equals(connKey)) {
                return existing;                  // still valid — reuse
            }
            if (existing != null) {
                stale.set(existing.client());     // config changed — rebuild, close old below
            }
            return new Entry(connKey, build(t, username, password));
        });
        closeQuietly(stale.get());
        return entry.client();
    }

    /**
     * Force-evict and close a tenant's cached clients, owner and read-only alike. Call when a
     * tenant is removed or its ClickHouse credentials are rotated; the next access rebuilds lazily.
     */
    public void invalidate(String tenantId) {
        boolean any = false;
        for (ConcurrentHashMap<String, Entry> clients : List.of(ownerClients, readOnlyClients)) {
            Entry removed = clients.remove(tenantId);
            if (removed != null) {
                closeQuietly(removed.client());
                any = true;
            }
        }
        // Cleared with the clients, so a re-provisioned tenant that still has no read-only user
        // says so again rather than being silently remembered as already warned.
        readOnlyFallbackWarned.remove(tenantId);
        if (any) {
            log.info("Invalidated ClickHouse clients for tenant {}", tenantId);
        }
    }

    /** Release a removed tenant's client when the tenant is dropped on a Vault refresh. */
    @EventListener
    public void onTenantRemoved(TenantRemovedEvent event) {
        invalidate(event.tenantId());
    }

    private static String connKey(Tenant t, String username, String password) {
        var ch = t.getClickHouseTenant();
        return ch.getHost() + "|" + ch.getDatabaseName() + "|" + username + "|" + password;
    }

    private Client build(Tenant t, String username, String password) {
        var ch = t.getClickHouseTenant();
        return new Client.Builder()
                .addEndpoint("http://" + ch.getHost() + ":8123")
                .setUsername(username)
                .setPassword(password)
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
        for (ConcurrentHashMap<String, Entry> clients : List.of(ownerClients, readOnlyClients)) {
            clients.values().forEach(e -> closeQuietly(e.client()));
            clients.clear();
        }
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
