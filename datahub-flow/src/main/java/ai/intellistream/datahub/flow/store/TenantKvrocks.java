// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.store;

import ai.intellistream.datahub.tenant.KVRocksTenant;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantRemovedEvent;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One connection to each tenant's own KVRocks, opened when it is first needed and kept: a Lettuce
 * connection is safe to share between threads and carries many commands at once, which the run
 * store needs, since dispatch and every finishing run use it at the same time.
 *
 * <p>A KVRocks that cannot be reached fails the call at once rather than queueing it for when it
 * comes back, so a tenant whose KVRocks is down gets errors, not a growing backlog of commands, and
 * the other tenants are unaffected.
 */
@Slf4j
@Component
public class TenantKvrocks implements AutoCloseable {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);

    private final TenantConfigService tenants;
    private final RedisClient client = RedisClient.create();
    private final Map<String, StatefulRedisConnection<String, String>> connections = new ConcurrentHashMap<>();

    public TenantKvrocks(TenantConfigService tenants) {
        this.tenants = tenants;
        client.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(COMMAND_TIMEOUT))
                .build());
    }

    /** The tenant's connection; opened on first use. */
    public StatefulRedisConnection<String, String> connection(String tenantId) {
        return connections.computeIfAbsent(tenantId, id -> {
            Tenant tenant = tenants.getConfig(id);
            KVRocksTenant config = tenant == null ? null : tenant.getKvrocksTenant();
            if (config == null || config.getHost() == null || config.getPort() == null) {
                throw new IllegalStateException("Tenant " + id + " has no kvrocks host and port configured, "
                        + "which flow runs are kept in.");
            }
            RedisURI.Builder uri = RedisURI.builder().withHost(config.getHost()).withPort(config.getPort())
                    .withTimeout(COMMAND_TIMEOUT);
            if (config.getPassword() != null && !config.getPassword().isBlank()) {
                uri.withPassword(config.getPassword().toCharArray());
            }
            log.info("Connecting to the KVRocks of tenant {} at {}:{}", id, config.getHost(), config.getPort());
            return client.connect(uri.build());
        });
    }

    @EventListener
    public void onTenantRemoved(TenantRemovedEvent event) {
        StatefulRedisConnection<String, String> removed = connections.remove(event.tenantId());
        if (removed != null) {
            removed.closeAsync();
        }
    }

    @Override
    public void close() {
        connections.values().forEach(StatefulRedisConnection::closeAsync);
        connections.clear();
        client.shutdown();
    }
}
