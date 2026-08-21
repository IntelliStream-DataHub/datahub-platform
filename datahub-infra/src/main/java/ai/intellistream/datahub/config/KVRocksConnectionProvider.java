// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.tenant.KVRocksTenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import org.springframework.stereotype.Component;

/**
 * Opens KVRocks connections for tenant data, routed to the current tenant's own KVRocks instance
 * (host/port from the tenant's Vault config under the {@code kvrocks} key). One shared RedisClient
 * is reused; a fresh byte[] connection is opened per call and closed by the caller.
 */
@Component
public class KVRocksConnectionProvider implements AutoCloseable {

    private final TenantConfigService tenantConfigService;
    private final RedisClient client = RedisClient.create();

    public KVRocksConnectionProvider(TenantConfigService tenantConfigService) {
        this.tenantConfigService = tenantConfigService;
    }

    /** Connection to the current tenant's KVRocks instance (tenant resolved from TenantContext). */
    public StatefulRedisConnection<byte[], byte[]> openConnection() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new RuntimeException("KVRocks multi-tenancy error: no Tenant ID in TenantContext. " +
                    "Ensure the request went through the TenantFilter.");
        }
        return openConnection(tenantId);
    }

    /**
     * Connection to a specific tenant's KVRocks instance. Use this from headless contexts (e.g. the
     * stateful consumer) where there is no {@link TenantContext} and the tenant id comes from the
     * Pulsar message instead.
     */
    public StatefulRedisConnection<byte[], byte[]> openConnection(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("KVRocks connection requested without a tenant id.");
        }
        KVRocksTenant config = tenantConfigService.getConfig(tenantId).getKvrocksTenant();
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(config.getHost())
                .withPort(config.getPort());
        if (config.getPassword() != null && !config.getPassword().isBlank()) {
            uri.withPassword(config.getPassword().toCharArray());
        }
        return client.connect(ByteArrayCodec.INSTANCE, uri.build());
    }

    @Override
    public void close() {
        client.shutdown();
    }
}
