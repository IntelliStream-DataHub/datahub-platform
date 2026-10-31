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
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Connections to each tenant's own KVRocks, opened when first needed and kept. The shared
 * {@link #connection} is safe between threads and carries many commands at once, which plain reads
 * and writes need, since dispatch and every finishing run use it at the same time. A transaction
 * ({@code WATCH}, {@code MULTI}, {@code EXEC}) belongs to one connection, so it cannot share: it
 * borrows one of a few {@link #dedicated} connections for its length.
 *
 * <p>A KVRocks that cannot be reached fails the call at once rather than queueing it for when it
 * comes back, so a tenant whose KVRocks is down gets errors, not a growing backlog of commands, and
 * the other tenants are unaffected.
 *
 * <p>Every key is kept under the tenant ({@link #codec}), so callers name keys without one.
 */
@Slf4j
@Component
public class TenantKvrocks implements AutoCloseable {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);
    /** Transactions one instance runs against a tenant's KVRocks at once. */
    private static final int DEDICATED = 16;

    /** A tenant's connections for transactions: the idle ones, and how many may be out. */
    private record Dedicated(Semaphore permits, ConcurrentLinkedQueue<StatefulRedisConnection<String, String>> idle) {
    }

    private final TenantConfigService tenants;
    private final RedisClient client = RedisClient.create();
    private final Map<String, StatefulRedisConnection<String, String>> connections = new ConcurrentHashMap<>();
    private final Map<String, Dedicated> dedicated = new ConcurrentHashMap<>();

    public TenantKvrocks(TenantConfigService tenants) {
        this.tenants = tenants;
        client.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(COMMAND_TIMEOUT))
                .build());
    }

    /** The tenant's shared connection; opened on first use. */
    public StatefulRedisConnection<String, String> connection(String tenantId) {
        return connections.computeIfAbsent(tenantId, this::connect);
    }

    /**
     * Runs {@code work} on a connection of its own, for a transaction. The connection goes back for
     * the next transaction when {@code work} returns, and is closed when it throws, since it may be
     * left watching keys or half way through a {@code MULTI}.
     */
    public <T> T dedicated(String tenantId, Function<StatefulRedisConnection<String, String>, T> work) {
        Dedicated pool = dedicated.computeIfAbsent(tenantId,
                id -> new Dedicated(new Semaphore(DEDICATED), new ConcurrentLinkedQueue<>()));
        try {
            if (!pool.permits().tryAcquire(COMMAND_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("No connection to the KVRocks of tenant " + tenantId
                        + " came free in " + COMMAND_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for a KVRocks connection", e);
        }
        StatefulRedisConnection<String, String> connection = null;
        try {
            connection = pool.idle().poll();
            if (connection == null || !connection.isOpen()) {
                connection = connect(tenantId);
            }
            T result = work.apply(connection);
            pool.idle().offer(connection);
            connection = null;
            return result;
        } finally {
            if (connection != null) {
                connection.closeAsync();
            }
            pool.permits().release();
        }
    }

    private StatefulRedisConnection<String, String> connect(String id) {
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
        return client.connect(codec(id), uri.build());
    }

    /**
     * Strings, with every key put under {@code {tenantId}:} on the way out and taken off on the way
     * back, so tenants may share a KVRocks without one taking another's runs. The braces make the
     * tenant the key's hash tag: in a cluster all of a tenant's keys share a slot, which the
     * transactions need.
     */
    static RedisCodec<String, String> codec(String tenantId) {
        String prefix = "{" + tenantId + "}:";
        return new RedisCodec<>() {
            @Override
            public String decodeKey(ByteBuffer bytes) {
                String key = StringCodec.UTF8.decodeKey(bytes);
                return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
            }

            @Override
            public String decodeValue(ByteBuffer bytes) {
                return StringCodec.UTF8.decodeValue(bytes);
            }

            @Override
            public ByteBuffer encodeKey(String key) {
                return StringCodec.UTF8.encodeKey(prefix + key);
            }

            @Override
            public ByteBuffer encodeValue(String value) {
                return StringCodec.UTF8.encodeValue(value);
            }
        };
    }

    @EventListener
    public void onTenantRemoved(TenantRemovedEvent event) {
        StatefulRedisConnection<String, String> removed = connections.remove(event.tenantId());
        if (removed != null) {
            removed.closeAsync();
        }
        Dedicated pool = dedicated.remove(event.tenantId());
        if (pool != null) {
            pool.idle().forEach(StatefulRedisConnection::closeAsync);
        }
    }

    @Override
    public void close() {
        connections.values().forEach(StatefulRedisConnection::closeAsync);
        connections.clear();
        dedicated.values().forEach(pool -> pool.idle().forEach(StatefulRedisConnection::closeAsync));
        dedicated.clear();
        client.shutdown();
    }
}
