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
     * This tenant's LLM configuration — one credential, shared by every agent it runs. Absent for
     * a tenant that has not been given its own model, in which case its agents fall back to the
     * deployment-wide default.
     *
     * <p>{@code @JsonIgnore} because it does <strong>not</strong> come from this secret: it lives
     * at {@code tenant-llm/<org-id>} so it can be written without granting write access to the
     * connection registry, and {@code TenantConfigService} fills it in after deserializing the
     * rest. See {@link TenantLlmStore}.
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
