// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

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

    public TenantFeatures getFeatures() {
        if (features == null) {
            features = new TenantFeatures();
        }
        return features;
    }

}
