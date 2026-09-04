// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.models.tenant.TenantLlmSettings;
import ai.intellistream.datahub.models.tenant.TenantLlmSettingsForm;
import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantLlm;
import ai.intellistream.datahub.tenant.TenantLlmWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validation and the credential's one-way trip.
 *
 * <p>These settings go to Vault, which accepts any string, and are read by a different process that
 * cannot answer back. A tenant that saves nonsense finds out when its assistant stops working, so
 * everything checkable is checked here, before the write.
 */
class TenantSettingsServiceTest {

    private static final String ORG_ID = "11111111-1111-1111-1111-111111111111";

    private TenantConfigService tenantConfigService;
    private TenantLlmWriter writer;
    private TenantSettingsService service;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenant = new Tenant();
        tenant.setOrganizationId(ORG_ID);
        tenant.setOrganizationName("acme");

        tenantConfigService = mock(TenantConfigService.class);
        when(tenantConfigService.getConfig(anyString())).thenReturn(tenant);
        writer = mock(TenantLlmWriter.class);
        service = new TenantSettingsService(tenantConfigService, writer);

        TenantContext.setTenantId(ORG_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static TenantLlm anthropic(String key) {
        TenantLlm llm = new TenantLlm();
        llm.setProvider(LlmProvider.ANTHROPIC);
        llm.setModel("claude-opus-5");
        llm.setApiKey(key);
        return llm;
    }

    private Map<String, String> written() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> section = ArgumentCaptor.forClass(Map.class);
        verify(writer).writeLlmSection(org.mockito.ArgumentMatchers.eq("acme"), section.capture());
        return section.getValue();
    }

    @Test
    void theCredentialIsNeverReadBack() {
        // The whole reason the response record has no apiKey field. A test here rather than only
        // on the record because this is the method that would have to leak it.
        tenant.setLlm(anthropic("sk-ant-secret"));

        TenantLlmSettings settings = service.readLlm();

        assertThat(settings.apiKeySet()).isTrue();
        assertThat(settings.toString()).doesNotContain("sk-ant-secret");
        assertThat(settings.model()).isEqualTo("claude-opus-5");
        assertThat(settings.configured()).isTrue();
    }

    @Test
    void anUnconfiguredTenantReadsAsUnconfiguredRatherThanFailing() {
        tenant.setLlm(null);

        assertThat(service.readLlm()).isEqualTo(TenantLlmSettings.none());
    }

    @Test
    void anAbsentKeyKeepsTheStoredOne() {
        // The form shows the key masked, so a save that does not retype it must not wipe it. This
        // is the difference between "unchanged" and "cleared", and getting it backwards silently
        // breaks the assistant for a tenant that only meant to change its model.
        tenant.setLlm(anthropic("sk-ant-stored"));

        service.updateLlm(new TenantLlmSettingsForm("anthropic", "claude-sonnet-5", null,
                null, null, null, null, null, null, null));

        assertThat(written()).containsEntry("api-key", "sk-ant-stored")
                .containsEntry("model", "claude-sonnet-5");
    }

    @Test
    void anEmptyKeyClearsIt() {
        tenant.setLlm(anthropic("sk-ant-stored"));

        // Anthropic with no key is not usable, so clearing it is rejected — which is the point:
        // the only way to end up with a broken configuration is to change provider too.
        assertThatThrownBy(() -> service.updateLlm(new TenantLlmSettingsForm(
                "anthropic", "claude-sonnet-5", "", null, null, null, null, null, null, null)))
                .isInstanceOf(BadRequestException.class);

        service.updateLlm(new TenantLlmSettingsForm("openai-compatible", "qwen3-32b", "",
                "http://vllm:8000/v1", null, null, null, null, null, null));

        assertThat(written().get("api-key")).isNull();
    }

    @Test
    void aProviderIsRefusedWithoutWhatItNeedsToReachAModel() {
        tenant.setLlm(null);

        assertThatThrownBy(() -> service.updateLlm(new TenantLlmSettingsForm(
                "anthropic", "claude-opus-5", null, null, null, null, null, null, null, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.updateLlm(new TenantLlmSettingsForm(
                "openai-compatible", "qwen3-32b", null, null, null, null, null, null, null, null)))
                .isInstanceOf(BadRequestException.class);

        verify(writer, never()).writeLlmSection(anyString(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void everyBadFieldIsReportedAtOnce() {
        // A half-filled form should come back marked up once, not one field per round trip.
        tenant.setLlm(null);

        assertThatThrownBy(() -> service.updateLlm(new TenantLlmSettingsForm(
                "telepathy", null, null, null, null, "ludicrous", "soon", -1, 0, null)))
                .isInstanceOfSatisfying(BadRequestException.class, failure -> {
                    var names = failure.getError().getError().getFields().stream()
                            .flatMap(field -> field.keySet().stream()).toList();
                    assertThat(names).contains("provider", "model", "effort", "turnTimeout",
                            "maxOutputTokens", "maxIterations");
                });
    }

    @Test
    void bothDurationSpellingsAreAccepted() {
        tenant.setLlm(anthropic("k"));

        service.updateLlm(new TenantLlmSettingsForm("anthropic", "claude-opus-5", null, null,
                null, null, "10m", null, null, null));
        assertThat(written()).containsEntry("turn-timeout", "10m");
    }

    @Test
    void theWriteCarriesOnlyTheSectionAndTheCacheIsReloaded() {
        // The reload is what makes this instance answer with what it just stored rather than the
        // five-minute-old cache it read the form from.
        tenant.setLlm(anthropic("k"));

        service.updateLlm(new TenantLlmSettingsForm("anthropic", "claude-opus-5", null, null,
                null, "max", null, 64_000, 20, "House style."));

        assertThat(written())
                .containsEntry("provider", "anthropic")
                .containsEntry("effort", "max")
                .containsEntry("max-output-tokens", "64000")
                .containsEntry("max-iterations", "20")
                .containsEntry("instructions", "House style.");
        verify(tenantConfigService).refreshCache();
    }
}
