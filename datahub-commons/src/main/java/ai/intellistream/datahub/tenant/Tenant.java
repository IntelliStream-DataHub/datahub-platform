// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Tenant {

    @JsonProperty("org-id")
    private String organizationId;
    private String organizationName;

    @JsonProperty("postgresql")
    private PostgresTenant postgresTenant;

    @JsonProperty("clickhouse")
    private ClickHouseTenant clickHouseTenant;

    @JsonProperty("neo4j")
    private Neo4jTenant neo4jTenant;

    @JsonProperty("valkey")
    private ValkeyTenant valkeyTenant;

    @JsonProperty("kvrocks")
    private KVRocksTenant kvrocksTenant;

    @JsonProperty("pulsar")
    private PulsarTenant pulsarTenant;

    @JsonProperty("file-storage")
    private FileStorage fileStorage;

    @JsonProperty("tenant-config")
    private TenantFeatures features;

    /**
     * This tenant's model configuration. Absent for a tenant that has not been given its own, in
     * which case the deployment-wide default applies.
     *
     * <p>{@code @JsonIgnore} because it does <strong>not</strong> come from this secret: it lives
     * in the tenant's own {@code tenant-config} secret, and {@code TenantConfigService} fills it in after
     * deserializing the rest. See {@link TenantLlmStore} for why it is separate.
     */
    @JsonIgnore
    private TenantLlm llm;

    public TenantFeatures getFeatures() {
        if (features == null) {
            features = new TenantFeatures();
        }
        return features;
    }

}
