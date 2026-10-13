// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow;

import ai.intellistream.datahub.flow.records.RecordsTopicProvisioner;
import ai.intellistream.datahub.flow.tenant.FlowSchemaCheck;
import ai.intellistream.datahub.tenant.OrganizationValidator;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantFeatures;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The service end to end through its real security chain: the token decoder is replaced by one that
 * accepts any bearer value but still runs {@link OrganizationValidator}, so the tenant is resolved
 * from the claim exactly as in production. Also proves the Arrow startup check passes under the
 * JVM flags the build gives the tests.
 */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.test/realms/datahub",
        "datahub.flow.runs.memory-budget=512MB",
        // Only resolved, never called: the tenant registry that would use it is mocked.
        "vault.address=http://vault.test", "vault.role-id=test", "vault.secret-id=test",
        // Read by PulsarConfig, whose client and admin beans are mocked below.
        "pulsar.service.url=pulsar://pulsar.test:6650", "pulsar.service.httpUrl=http://pulsar.test:8080",
        "pulsar.client-id=test"
})
class FlowServiceTest {

    private static final String FOO = "11111111-1111-1111-1111-111111111111";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterRegistrationBean<?> tenantContextCleanupFilter;

    @Autowired
    private DataSize runMemoryBudget;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private TenantConfigService tenantConfigService;

    @MockitoBean
    private FlowSchemaCheck schemaCheck;

    // No broker here; RecordsTopicProvisionerIT covers provisioning against a real one.
    @MockitoBean
    private PulsarClient pulsarClient;

    @MockitoBean
    private PulsarAdmin pulsarAdmin;

    @MockitoBean
    private RecordsTopicProvisioner recordsTopicProvisioner;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // The cleanup filter is a servlet filter, which webAppContextSetup does not pick up by itself.
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(tenantContextCleanupFilter.getFilter())
                .apply(springSecurity())
                .build();

        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> {
            Jwt jwt = Jwt.withTokenValue(invocation.getArgument(0))
                    .header("alg", "none")
                    .subject("user-foo")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .claim("realm_access", Map.of("roles", List.of("DATAHUB_ACCESS")))
                    .claim("organization", Map.of("foo", Map.of("id", FOO)))
                    .build();
            // What the real decoder's validator chain does: resolve the tenant into TenantContext.
            assertThat(new OrganizationValidator().validate(jwt).hasErrors()).isFalse();
            return jwt;
        });
    }

    private void tenantWithFlows(boolean enabled) {
        TenantFeatures features = new TenantFeatures();
        features.setFlowFeatureEnabled(enabled);
        Tenant tenant = new Tenant();
        tenant.setFeatures(features);
        when(tenantConfigService.getConfig(FOO)).thenReturn(tenant);
    }

    @Test
    void theCatalogAnswersForATenantWithFlows() throws Exception {
        tenantWithFlows(true);
        when(schemaCheck.isProvisioned(FOO)).thenReturn(true);

        mvc.perform(get("/catalog/processors").header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12))
                .andExpect(jsonPath("$[0].name").value("datahub.events.sink"));
        assertThat(TenantContext.getTenantId()).as("cleared after the request").isNull();
    }

    @Test
    void aTenantWithoutTheFlagIsRefused() throws Exception {
        tenantWithFlows(false);

        mvc.perform(get("/catalog/processors").header("Authorization", "Bearer t"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/feature-disabled"))
                .andExpect(jsonPath("$.feature").value("flow"));
    }

    @Test
    void anUnknownTenantIsRefused() throws Exception {
        mvc.perform(get("/catalog/processors").header("Authorization", "Bearer t"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/unknown-tenant"));
    }

    @Test
    void aTenantWithoutTheSchemaIsAskedToRetry() throws Exception {
        tenantWithFlows(true);
        when(schemaCheck.isProvisioned(FOO)).thenReturn(false);

        mvc.perform(get("/catalog/processors").header("Authorization", "Bearer t"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/tenant-provisioning"));
    }

    @Test
    void noTokenNoEntry() throws Exception {
        mvc.perform(get("/catalog/processors")).andExpect(status().isUnauthorized());
    }

    @Test
    void theRunMemoryBudgetIsTheConfiguredOne() {
        assertThat(runMemoryBudget).isEqualTo(DataSize.ofMegabytes(512));
    }
}
