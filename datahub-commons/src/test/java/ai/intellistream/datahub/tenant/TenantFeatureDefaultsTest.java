// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import ai.intellistream.datahub.config.VaultProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureDefaultsTest {

    private static final VaultProperties VAULT =
            VaultProperties.of("http://vault.invalid:8200", "test", "test");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private TenantConfigService service;

    @BeforeEach
    void setUp() {
        service = new TenantConfigService(jsonMapper, null, VAULT);
        ReflectionTestUtils.setField(service, "policyFeatureDefault", true);
        ReflectionTestUtils.setField(service, "streamingFeatureDefault", true);
    }

    @Test
    void flagsAbsentInVaultFallBackToApplicationDefaults() {
        Tenant tenant = jsonMapper.readValue("""
                {"org-id": "t1", "tenant-config": {"files": true}}
                """, Tenant.class);

        service.applyFeatureDefaults(tenant.getFeatures());

        assertThat(tenant.getFeatures().isPolicyFeatureEnabled()).isTrue();
        assertThat(tenant.getFeatures().isStreamingFeatureEnabled()).isTrue();
        assertThat(tenant.getFeatures().isFilesEnabled()).isTrue();
    }

    @Test
    void explicitVaultValueWinsOverApplicationDefault() {
        Tenant tenant = jsonMapper.readValue("""
                {"org-id": "t1", "tenant-config": {"policy": false, "streaming": true}}
                """, Tenant.class);

        service.applyFeatureDefaults(tenant.getFeatures());

        assertThat(tenant.getFeatures().isPolicyFeatureEnabled()).isFalse();
        assertThat(tenant.getFeatures().isStreamingFeatureEnabled()).isTrue();
    }

    @Test
    void missingTenantConfigBlockGetsDefaults() {
        Tenant tenant = jsonMapper.readValue("""
                {"org-id": "t1"}
                """, Tenant.class);

        service.applyFeatureDefaults(tenant.getFeatures());

        assertThat(tenant.getFeatures().isPolicyFeatureEnabled()).isTrue();
        assertThat(tenant.getFeatures().isStreamingFeatureEnabled()).isTrue();
        assertThat(tenant.getFeatures().isFilesEnabled()).isFalse();
    }

    @Test
    void serializedFeatureKeysMatchTenantConfigContract() {
        TenantFeatures features = new TenantFeatures();
        features.setFilesEnabled(true);
        features.setPolicyFeatureEnabled(true);
        features.setStreamingFeatureEnabled(false);

        String json = jsonMapper.writeValueAsString(features);

        assertThat(json).contains("\"files\":true")
                .contains("\"policy\":true")
                .contains("\"streaming\":false");
    }
}
