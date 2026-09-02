// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

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
     * Named LLM backends this tenant's agents may point at, keyed by the name an agent's
     * {@code backend_ref} names. Absent for every tenant that has not been given its own model,
     * in which case agents fall back to the deployment-wide default.
     */
    @JsonProperty("llm-backends")
    private Map<String, TenantLlmBackend> llmBackends;

    public TenantFeatures getFeatures() {
        if (features == null) {
            features = new TenantFeatures();
        }
        return features;
    }

    /** Never null, so callers can look a backend up without first checking for the block. */
    public Map<String, TenantLlmBackend> getLlmBackends() {
        return llmBackends == null ? Map.of() : llmBackends;
    }

}
