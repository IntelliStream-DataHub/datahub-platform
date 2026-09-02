// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.agent.AgentDefinition;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.datasecurity.SettingsAccessDeniedException;
import ai.intellistream.datahub.api.datasecurity.SettingsSecurity;
import ai.intellistream.datahub.api.mcp.ToolCatalog;
import ai.intellistream.datahub.errors.ObjectNotFoundException;
import ai.intellistream.datahub.jpa.domains.AgentEntity;
import ai.intellistream.datahub.repositories.agent.AgentRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Writing an agent definition.
 *
 * <p>What is worth testing here is refusal. An agent's allowlist is a list of bare strings that
 * silently produce nothing when wrong, so every way of getting one wrong has to be rejected at
 * the point it is written — the only moment the mistake is still attributable to anyone.
 */
class AgentServiceTest {

    private final AgentRepository repository = mock(AgentRepository.class);
    private final SettingsSecurity settingsSecurity = mock(SettingsSecurity.class);
    private final AgentService service =
            new AgentService(repository, new ToolCatalog(), settingsSecurity);

    private static AgentDefinition definition(List<String> tools) {
        return new AgentDefinition("assistant", "Assistant", null, tools, null, null, null, true);
    }

    /**
     * The message a caller actually receives. {@code BadRequestException} carries its detail in
     * the {@code ResponseError} body it is rendered as, not in {@code getMessage()}, so asserting
     * on the throwable's message would pass vacuously against null.
     */
    private static String clientMessage(Throwable thrown) {
        return ((BadRequestException) thrown).getError().getError().getMessage();
    }

    private void save(AgentDefinition definition) {
        when(repository.findByExternalId("assistant")).thenReturn(Optional.empty());
        when(repository.save(any(AgentEntity.class))).thenAnswer(i -> i.getArgument(0));
        service.save("assistant", definition);
    }

    @Test
    void acceptsAnAllowlistOfKnownReadOnlyTools() {
        when(repository.findByExternalId("assistant")).thenReturn(Optional.empty());
        when(repository.save(any(AgentEntity.class))).thenAnswer(i -> i.getArgument(0));

        AgentDefinition saved = service.save("assistant",
                definition(List.of("event_search", "timeseries_get", "analysis_related_series")));

        assertThat(saved.toolAllowlist())
                .containsExactly("event_search", "timeseries_get", "analysis_related_series");
        assertThat(saved.externalId()).isEqualTo("assistant");
    }

    @Test
    void refusesAToolNameNoServerServes() {
        // The failure this exists to prevent: a typo produces an assistant that quietly cannot do
        // something, indistinguishable at run time from a permission it lacks.
        assertThatThrownBy(() -> save(definition(List.of("event_search", "event_serach"))))
                .isInstanceOf(BadRequestException.class)
                .extracting(AgentServiceTest::clientMessage).asString()
                .contains("event_serach")
                .contains("GET /agents/tools");

        verify(repository, never()).save(any());
    }

    @Test
    void refusesAMutatingTool() {
        assertThatThrownBy(() -> save(definition(List.of("event_search", "resource_delete"))))
                .isInstanceOf(BadRequestException.class)
                .extracting(AgentServiceTest::clientMessage).asString()
                .contains("resource_delete");

        verify(repository, never()).save(any());
    }

    @Test
    void namesEveryOffendingToolAtOnceRatherThanTheFirst() {
        assertThatThrownBy(() -> save(definition(List.of("resource_create", "event_delete"))))
                .isInstanceOf(BadRequestException.class)
                .extracting(AgentServiceTest::clientMessage).asString()
                .contains("resource_create")
                .contains("event_delete");
    }

    @Test
    void anEmptyAllowlistIsAllowedBecauseItMeansSomething() {
        // An agent with no tools is a coherent thing to define — it just answers from the
        // conversation. What must never happen is empty being read as "all tools".
        when(repository.findByExternalId("assistant")).thenReturn(Optional.empty());
        when(repository.save(any(AgentEntity.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.save("assistant", definition(List.of())).toolAllowlist()).isEmpty();
    }

    @Test
    void refusesAnEffortLevelThatIsNotOne() {
        AgentDefinition bad = new AgentDefinition("assistant", "Assistant", null,
                List.of("event_search"), "hardest", null, null, true);

        assertThatThrownBy(() -> save(bad))
                .isInstanceOf(BadRequestException.class)
                .extracting(AgentServiceTest::clientMessage).asString()
                .contains("hardest")
                .contains("low, medium, high, xhigh, max");
    }

    @Test
    void acceptsEveryRealEffortLevel() {
        for (String level : List.of("low", "medium", "high", "xhigh", "max")) {
            when(repository.findByExternalId("assistant")).thenReturn(Optional.empty());
            when(repository.save(any(AgentEntity.class))).thenAnswer(i -> i.getArgument(0));

            AgentDefinition definition = new AgentDefinition("assistant", "Assistant", null,
                List.of("event_search"), level, null, null, true);

            assertThat(service.save("assistant", definition).defaultEffort()).isEqualTo(level);
        }
    }

    @Test
    void refusesABudgetThatWouldDefineAnAgentThatCanNeverAnswer() {
        AgentDefinition noIterations = new AgentDefinition("assistant", "Assistant", null,
                List.of("event_search"), null, null, 0, true);
        assertThatThrownBy(() -> save(noIterations)).isInstanceOf(BadRequestException.class);

        AgentDefinition noTokens = new AgentDefinition("assistant", "Assistant", null,
                List.of("event_search"), null, 0, null, true);
        assertThatThrownBy(() -> save(noTokens)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void refusesAnAgentWithNoDisplayName() {
        AgentDefinition nameless = new AgentDefinition("assistant", "  ", null, List.of(), null, null, null, true);

        assertThatThrownBy(() -> save(nameless)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void writingRequiresTheSettingsWriteGroup() {
        // Configuring an assistant is a power over configuration, not over data: it used to need an
        // all-datasets write grant, which meant the only way to let someone curate agents was to
        // hand them every row in the tenant.
        doThrow(SettingsAccessDeniedException.write())
                .when(settingsSecurity).assertCanWriteSettings();

        assertThatThrownBy(() -> service.save("assistant", definition(List.of("event_search"))))
                .isInstanceOf(SettingsAccessDeniedException.class)
                .hasMessageContaining("/settings/write");
        assertThatThrownBy(() -> service.delete("assistant"))
                .isInstanceOf(SettingsAccessDeniedException.class);

        verify(repository, never()).save(any());
        verify(repository, never()).delete(any());
    }

    @Test
    void theGrantIsCheckedBeforeTheBodyIsValidated() {
        // Otherwise the validation messages tell an unauthorised caller which tools exist.
        doThrow(SettingsAccessDeniedException.write())
                .when(settingsSecurity).assertCanWriteSettings();

        assertThatThrownBy(() -> service.save("assistant", definition(List.of("event_serach"))))
                .isInstanceOf(SettingsAccessDeniedException.class);
    }

    @Test
    void listingRequiresTheSettingsReadGroup() {
        // The management view. Fetching one agent by name is deliberately not gated — that is how
        // the console learns what it is running, and gating it would mean granting the settings
        // group to everyone who uses the assistant.
        doThrow(SettingsAccessDeniedException.read())
                .when(settingsSecurity).assertCanReadSettings();

        assertThatThrownBy(service::list)
                .isInstanceOf(SettingsAccessDeniedException.class)
                .hasMessageContaining("/settings/read");
    }

    @Test
    void fetchingOneAgentToRunItNeedsNoSettingsGrant() {
        doThrow(SettingsAccessDeniedException.read())
                .when(settingsSecurity).assertCanReadSettings();
        AgentEntity existing = new AgentEntity();
        existing.setExternalId("assistant");
        existing.setDisplayName("Assistant");
        existing.setToolAllowlist(new java.util.ArrayList<>(List.of("event_search")));
        when(repository.findByExternalId("assistant")).thenReturn(Optional.of(existing));

        assertThat(service.get("assistant").toolAllowlist()).containsExactly("event_search");
    }

    @Test
    void anUnknownAgentIsNotFoundRatherThanNull() {
        when(repository.findByExternalId("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("ghost"))
                .isInstanceOf(ObjectNotFoundException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void savingAnExistingAgentUpdatesItRatherThanAddingASecond() {
        AgentEntity existing = new AgentEntity();
        existing.setExternalId("assistant");
        existing.setDisplayName("Old name");
        existing.setToolAllowlist(new java.util.ArrayList<>(List.of("unit_list")));
        when(repository.findByExternalId("assistant")).thenReturn(Optional.of(existing));
        when(repository.save(any(AgentEntity.class))).thenAnswer(i -> i.getArgument(0));

        service.save("assistant", definition(List.of("event_search")));

        assertThat(existing.getDisplayName()).isEqualTo("Assistant");
        assertThat(existing.getToolAllowlist()).containsExactly("event_search");
    }
}
