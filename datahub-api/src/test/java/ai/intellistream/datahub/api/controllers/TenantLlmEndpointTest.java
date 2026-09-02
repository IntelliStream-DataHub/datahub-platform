// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.SettingsAccessDeniedException;
import ai.intellistream.datahub.api.datasecurity.SettingsSecurity;
import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.tenant.TenantLlm;
import ai.intellistream.datahub.tenant.TenantLlmForm;
import ai.intellistream.datahub.tenant.TenantLlmStore;
import ai.intellistream.datahub.tenant.TenantLlmView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET} and {@code PUT /tenant/llm}.
 *
 * <p>Two properties carry all the risk. The API key must never come back out, because there is no
 * screen on which its value is useful and every way of returning it is a way of leaking it. And an
 * absent key on a save must mean "leave it", because a form cannot show the current key — treating
 * the field it could not populate as "clear it" would delete the credential every time anyone
 * edited the model name.
 */
class TenantLlmEndpointTest {

    private static final String TENANT = "org-1";

    private final TenantLlmStore store = mock(TenantLlmStore.class);
    private final SettingsSecurity settingsSecurity = mock(SettingsSecurity.class);
    private final TenantConfigService tenantConfigService = mock(TenantConfigService.class);
    private final TenantController controller = new TenantController(
            tenantConfigService, mock(DataSecurity.class), settingsSecurity, store);

    @BeforeEach
    void enterTenant() {
        TenantContext.setTenantId(TENANT);
    }

    @AfterEach
    void leaveTenant() {
        TenantContext.clear();
    }

    private static TenantLlm stored(String apiKey, String model) {
        TenantLlm llm = new TenantLlm();
        llm.setProvider(LlmProvider.ANTHROPIC);
        llm.setApiKey(apiKey);
        llm.setModel(model);
        return llm;
    }

    private static TenantLlmForm form(String model, String apiKey) {
        return new TenantLlmForm("anthropic", model, null, null, null, apiKey);
    }

    private TenantLlm written() {
        ArgumentCaptor<TenantLlm> captor = ArgumentCaptor.forClass(TenantLlm.class);
        verify(store).write(anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    void neverReturnsTheApiKey() {
        when(store.read(TENANT)).thenReturn(stored("sk-ant-secret", "claude-opus-5"));

        TenantLlmView view = controller.getLlm().getBody();

        assertThat(view.hasApiKey()).isTrue();
        assertThat(view.model()).isEqualTo("claude-opus-5");
        // The record has no field for it, so this is really a check that nobody adds one.
        assertThat(view.toString()).doesNotContain("sk-ant-secret");
    }

    @Test
    void saysWhenTheTenantIsStillOnTheDeploymentDefault() {
        when(store.read(TENANT)).thenReturn(null);

        TenantLlmView view = controller.getLlm().getBody();

        assertThat(view.configured()).isFalse();
        assertThat(view.hasApiKey()).isFalse();
        assertThat(view.provider()).isNull();
    }

    @Test
    void anAbsentKeyLeavesTheStoredOneAlone() {
        // The case that matters: someone edits the model name on a form that could not show them
        // the key. Clearing it here would be silent and destructive.
        when(store.read(TENANT)).thenReturn(stored("sk-ant-existing", "claude-sonnet-5"));

        controller.putLlm(form("claude-opus-5", null));

        assertThat(written().getApiKey()).isEqualTo("sk-ant-existing");
        assertThat(written().getModel()).isEqualTo("claude-opus-5");
    }

    @Test
    void anEmptyKeyClearsItDeliberately() {
        // Distinct from absent, because a tenant moving to a self-hosted model needs a way to say
        // "there is no key now" and the two must not collapse into each other.
        when(store.read(TENANT)).thenReturn(stored("sk-ant-existing", "claude-opus-5"));

        controller.putLlm(form("qwen3-32b", ""));

        assertThat(written().getApiKey()).isNull();
    }

    @Test
    void aSuppliedKeyReplacesTheStoredOne() {
        when(store.read(TENANT)).thenReturn(stored("sk-ant-old", "claude-opus-5"));

        controller.putLlm(form("claude-opus-5", "sk-ant-new"));

        assertThat(written().getApiKey()).isEqualTo("sk-ant-new");
    }

    @Test
    void aFirstConfigurationNeedsNoExistingKeyToPreserve() {
        when(store.read(TENANT)).thenReturn(null);

        controller.putLlm(form("claude-opus-5", "sk-ant-new"));

        assertThat(written().getApiKey()).isEqualTo("sk-ant-new");
    }

    @Test
    void refreshesTheCacheSoASaveIsVisibleImmediately() {
        // Without this the settings page keeps showing the old model for up to five minutes, which
        // reads as a failed save and invites the user to do it again.
        when(store.read(TENANT)).thenReturn(null);

        controller.putLlm(form("claude-opus-5", "sk-ant-new"));

        verify(tenantConfigService).refreshLlm(TENANT);
    }

    @Test
    void readingNeedsTheSettingsReadGroup() {
        doThrow(SettingsAccessDeniedException.read()).when(settingsSecurity).assertCanReadSettings();

        assertThatThrownBy(controller::getLlm)
                .isInstanceOf(SettingsAccessDeniedException.class)
                .hasMessageContaining("/settings/read");
    }

    @Test
    void writingNeedsTheSettingsWriteGroupAndIsCheckedFirst() {
        // Checked before anything is read or written, so a refused caller learns nothing about the
        // configuration and cannot cause a Vault round trip.
        doThrow(SettingsAccessDeniedException.write()).when(settingsSecurity).assertCanWriteSettings();

        assertThatThrownBy(() -> controller.putLlm(form("claude-opus-5", "sk-ant-new")))
                .isInstanceOf(SettingsAccessDeniedException.class)
                .hasMessageContaining("/settings/write");

        verify(store, never()).write(anyString(), any());
        verify(store, never()).read(anyString());
    }

    @Test
    void blankTextFieldsBecomeNullRatherThanEmptyStrings() {
        // A form posts "" for every field the user left alone. Storing those would make an empty
        // model name look configured, and the deployment default would stop filling it in.
        when(store.read(TENANT)).thenReturn(null);

        controller.putLlm(new TenantLlmForm("", "  ", "", null, "  ", "k"));

        TenantLlm saved = written();
        assertThat(saved.getProvider()).isNull();
        assertThat(saved.getModel()).isNull();
        assertThat(saved.getBaseUrl()).isNull();
        assertThat(saved.getTurnTimeout()).isNull();
    }
}
