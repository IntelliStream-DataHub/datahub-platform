// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.store;

import ai.intellistream.datahub.tenant.KVRocksTenant;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.sync.RedisCommands;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KVRocks containers, started on first use and stopped by a JVM hook, shared by every test that
 * needs one (as {@code SharedPostgres} does for Postgres). Most tests need one server; a test of
 * several tenants asks for one server per tenant, as the service has. Each test starts from empty
 * servers.
 */
public final class SharedKvrocks {

    private static final List<GenericContainer<?>> CONTAINERS = new ArrayList<>();
    private static final List<RedisCommands<String, String>> ADMINS = new ArrayList<>();
    private static final Map<String, RedisCommands<String, String>> VIEWS = new ConcurrentHashMap<>();
    private static RedisClient client;

    static {
        ensure(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> CONTAINERS.forEach(GenericContainer::stop)));
    }

    private SharedKvrocks() {
    }

    /** Starts servers until there are at least {@code n}. */
    public static synchronized void ensure(int n) {
        while (CONTAINERS.size() < n) {
            GenericContainer<?> container = new GenericContainer<>(
                    DockerImageName.parse("docker.io/apache/kvrocks:2.14.0"))
                    .withExposedPorts(6666).waitingFor(Wait.forListeningPort());
            container.start();
            if (client == null) {
                client = RedisClient.create();
            }
            CONTAINERS.add(container);
            ADMINS.add(client.connect(RedisURI.create(container.getHost(), container.getMappedPort(6666))).sync());
        }
    }

    /** Where the {@code i}th server is, as a tenant's configuration. */
    public static KVRocksTenant kvrocks(int i) {
        ensure(i + 1);
        KVRocksTenant kvrocks = new KVRocksTenant();
        kvrocks.setHost(CONTAINERS.get(i).getHost());
        kvrocks.setPort(CONTAINERS.get(i).getMappedPort(6666));
        return kvrocks;
    }

    /** The tenant registry with one tenant, whose KVRocks is the first server. */
    public static TenantConfigService tenants(String tenantId) {
        Tenant tenant = new Tenant();
        tenant.setOrganizationId(tenantId);
        tenant.setKvrocksTenant(kvrocks(0));
        TenantConfigService service = mock(TenantConfigService.class);
        when(service.getConfig(anyString())).thenReturn(tenant);
        return service;
    }

    /** Empties every server. */
    public static synchronized void flush() {
        ADMINS.forEach(RedisCommands::flushall);
    }

    /**
     * The current tenant's keys on the first server, for looking at them as the invariants do. Its
     * keys are named without the tenant, as the store names them.
     */
    public static RedisCommands<String, String> raw() {
        return raw(0);
    }

    /** The current tenant's keys on the {@code i}th server. */
    public static synchronized RedisCommands<String, String> raw(int i) {
        ensure(i + 1);
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant on the context to look at the keys of");
        }
        return VIEWS.computeIfAbsent(i + "|" + tenantId, view -> client.connect(TenantKvrocks.codec(tenantId),
                RedisURI.create(CONTAINERS.get(i).getHost(), CONTAINERS.get(i).getMappedPort(6666))).sync());
    }

    /**
     * The current tenant's keys matching {@code pattern} on the first server, named as the store
     * names them. {@code KEYS} on {@link #raw} would not do: Lettuce sends its pattern without the
     * codec, so it would match no tenant's keys.
     */
    public static List<String> keys(String pattern) {
        return keys(0, pattern);
    }

    /** The current tenant's keys matching {@code pattern} on the {@code i}th server. */
    public static List<String> keys(int i, String pattern) {
        RedisCommands<String, String> view = raw(i);
        String prefix = "{" + TenantContext.getTenantId() + "}:";
        return view.keys(prefix + pattern);
    }
}
