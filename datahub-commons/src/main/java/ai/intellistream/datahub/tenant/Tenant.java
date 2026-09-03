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

    /**
     * What this tenant has been given: the feature entitlements, from the {@code tenant-config}
     * block of <em>this</em> secret. Operator-set — a customer does not grant itself a feature.
     */
    @JsonProperty("tenant-config")
    private TenantFeatures features;

    /**
     * This tenant's model configuration. Absent for a tenant that has not configured one, which
     * means it has no assistant — there is no deployment-wide model behind this.
     *
     * <p>{@code @JsonIgnore} because it does <strong>not</strong> come from this secret: it lives
     * in the separate {@code tenant-config/<org-name>} secret, and {@code TenantConfigService}
     * fills it in after deserializing the rest. See {@link TenantLlmStore} for why.
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
