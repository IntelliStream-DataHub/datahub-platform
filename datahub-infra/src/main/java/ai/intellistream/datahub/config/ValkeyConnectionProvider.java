// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.ValkeyTenant;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supplies Valkey connections for tenant data, routed to the current tenant's own Valkey instance
 * (host/port from the tenant's Vault config under the {@code valkey} key).
 *
 * <p>One shared {@link RedisClient}, and <strong>one long-lived connection per tenant</strong>.
 * Lettuce connections are thread-safe and multiplex commands over a single socket, so sharing one
 * is both correct and much cheaper than connecting per call: a cache lookup on the request path
 * would otherwise pay a TCP handshake (plus an AUTH round trip) every single time.
 *
 * <p><strong>Callers must not close the returned connection.</strong> It is shared and this
 * provider owns its lifetime, which is why the accessor is {@link #connection()} rather than
 * {@code openConnection()} — there is nothing to open and nothing for the caller to release.
 *
 * <p>A shared connection is only safe for auto-flush, non-transactional use. Anything that turns
 * auto-flush off, runs {@code MULTI}/{@code EXEC}, or subscribes to pub/sub sets connection-wide
 * state and must open a connection of its own instead. Compare {@code KVRocksConnectionProvider},
 * whose callers use transactions and therefore still connect per call.
 *
 * <p>This is separate from the console's global session-store Valkey, which is configured on its own.
 */
@Component
@Slf4j
public class ValkeyConnectionProvider implements AutoCloseable {

    private final TenantConfigService tenantConfigService;
    private final RedisClient client = RedisClient.create();
    private final Map<String, StatefulRedisConnection<String, String>> connections = new ConcurrentHashMap<>();

    public ValkeyConnectionProvider(TenantConfigService tenantConfigService) {
        this.tenantConfigService = tenantConfigService;
    }

    /**
     * The current tenant's shared Valkey connection (tenant resolved from {@link TenantContext}),
     * opened on first use. Do not close the returned connection.
     */
    public StatefulRedisConnection<String, String> connection() {
        String tenantId = currentTenantId();

        // Fast path: already open, so no map write and no lock.
        StatefulRedisConnection<String, String> existing = connections.get(tenantId);
        if (existing != null && existing.isOpen()) {
            return existing;
        }

        // Lettuce reconnects on its own, so isOpen() is false only after an explicit close or once
        // reconnect attempts are exhausted. compute() gives single-flight: concurrent callers for
        // the same tenant open one connection between them rather than one each.
        return connections.compute(tenantId, (id, current) -> {
            if (current != null && current.isOpen()) {
                return current;
            }
            if (current != null) {
                current.close();
            }
            return connect(id);
        });
    }

    /**
     * Drop and close the cached connection for a tenant. Call this when a tenant's Valkey config
     * changes or the tenant is removed, otherwise the stale host/credentials stay in use for the
     * lifetime of the process. The next {@link #connection()} reconnects using fresh config.
     */
    public void evict(String tenantId) {
        StatefulRedisConnection<String, String> removed = connections.remove(tenantId);
        if (removed != null) {
            removed.close();
        }
    }

    private StatefulRedisConnection<String, String> connect(String tenantId) {
        ValkeyTenant config = tenantConfigService.getConfig(tenantId).getValkeyTenant();
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(config.getHost())
                .withPort(config.getPort());
        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            uri.withAuthentication(config.getUsername(), config.getPassword());
        }
        log.debug("Opening Valkey connection for tenant {} to {}:{}",
                tenantId, config.getHost(), config.getPort());
        return client.connect(StringCodec.UTF8, uri.build());
    }

    private String currentTenantId() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new RuntimeException("Valkey multi-tenancy error: no Tenant ID in TenantContext. " +
                    "Ensure the request went through the TenantFilter.");
        }
        return tenantId;
    }

    @Override
    public void close() {
        connections.values().forEach(StatefulRedisConnection::close);
        connections.clear();
        client.shutdown();
    }
}
