// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.tenant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TenantFeatures {

    @JsonProperty("files")
    private boolean filesEnabled;

    // Boolean, not boolean: null means "not set in the tenant's Vault block", which lets
    // TenantConfigService fall back to the application.yml default. An explicit value in
    // Vault always wins over the yml default.
    @JsonProperty("policy")
    private Boolean policyFeatureEnabled;

    @JsonProperty("streaming")
    private Boolean streamingFeatureEnabled;

    @JsonProperty("chat")
    private Boolean chatFeatureEnabled;

    @JsonIgnore
    public boolean isPolicyFeatureEnabled() {
        return Boolean.TRUE.equals(policyFeatureEnabled);
    }

    @JsonIgnore
    public boolean isStreamingFeatureEnabled() {
        return Boolean.TRUE.equals(streamingFeatureEnabled);
    }

    @JsonIgnore
    public boolean isChatFeatureEnabled() {
        return Boolean.TRUE.equals(chatFeatureEnabled);
    }

}
