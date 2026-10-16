// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.store;

import ai.intellistream.datahub.tenant.KVRocksTenant;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.sync.RedisCommands;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One KVRocks container, started on first use and stopped by a JVM hook, shared by every test that
 * needs one (as {@code SharedPostgres} does for Postgres). Each test starts from an empty server.
 */
public final class SharedKvrocks {

    private static final GenericContainer<?> CONTAINER = new GenericContainer<>(
            DockerImageName.parse("docker.io/apache/kvrocks:2.14.0"))
            .withExposedPorts(6666).waitingFor(Wait.forListeningPort());

    private static final RedisClient CLIENT;
    private static final RedisCommands<String, String> ADMIN;

    static {
        CONTAINER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(CONTAINER::stop));
        CLIENT = RedisClient.create(RedisURI.create(CONTAINER.getHost(), CONTAINER.getMappedPort(6666)));
        ADMIN = CLIENT.connect().sync();
    }

    private SharedKvrocks() {
    }

    /** The tenant registry with one tenant, whose KVRocks is this container. */
    public static TenantConfigService tenants(String tenantId) {
        KVRocksTenant kvrocks = new KVRocksTenant();
        kvrocks.setHost(CONTAINER.getHost());
        kvrocks.setPort(CONTAINER.getMappedPort(6666));
        Tenant tenant = new Tenant();
        tenant.setOrganizationId(tenantId);
        tenant.setKvrocksTenant(kvrocks);
        TenantConfigService service = mock(TenantConfigService.class);
        when(service.getConfig(anyString())).thenReturn(tenant);
        return service;
    }

    /** Empties the server. */
    public static void flush() {
        ADMIN.flushall();
    }

    /** A connection for looking at the raw keys, as the invariants do. */
    public static RedisCommands<String, String> raw() {
        return ADMIN;
    }
}
