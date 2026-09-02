// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.agent.AgentDefinition;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetAccessDeniedException;
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
    private final DataSecurity dataSecurity = mock(DataSecurity.class);
    private final AgentService service = new AgentService(repository, new ToolCatalog(), dataSecurity);

    private static AgentDefinition definition(List<String> tools) {
        return new AgentDefinition("assistant", "Assistant", null, null, tools, null, null, null, true);
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
        AgentDefinition bad = new AgentDefinition("assistant", "Assistant", null, null,
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

            AgentDefinition definition = new AgentDefinition("assistant", "Assistant", null, null,
                    List.of("event_search"), level, null, null, true);

            assertThat(service.save("assistant", definition).defaultEffort()).isEqualTo(level);
        }
    }

    @Test
    void refusesABudgetThatWouldDefineAnAgentThatCanNeverAnswer() {
        AgentDefinition noIterations = new AgentDefinition("assistant", "Assistant", null, null,
                List.of("event_search"), null, null, 0, true);
        assertThatThrownBy(() -> save(noIterations)).isInstanceOf(BadRequestException.class);

        AgentDefinition noTokens = new AgentDefinition("assistant", "Assistant", null, null,
                List.of("event_search"), null, 0, null, true);
        assertThatThrownBy(() -> save(noTokens)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void refusesAnAgentWithNoDisplayName() {
        AgentDefinition nameless = new AgentDefinition("assistant", "  ", null, null,
                List.of(), null, null, null, true);

        assertThatThrownBy(() -> save(nameless)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void writingRequiresTheGrantThatManagesDatasets() {
        // An agent's tool list governs what an assistant may reach across the whole tenant, so a
        // per-dataset grant must not confer it.
        doThrow(DatasetAccessDeniedException.datasetManagement())
                .when(dataSecurity).assertCanManageDataSets();

        assertThatThrownBy(() -> service.save("assistant", definition(List.of("event_search"))))
                .isInstanceOf(DatasetAccessDeniedException.class);
        assertThatThrownBy(() -> service.delete("assistant"))
                .isInstanceOf(DatasetAccessDeniedException.class);

        verify(repository, never()).save(any());
        verify(repository, never()).delete(any());
    }

    @Test
    void theGrantIsCheckedBeforeTheBodyIsValidated() {
        // Otherwise the validation messages tell an unauthorised caller which tools exist.
        doThrow(DatasetAccessDeniedException.datasetManagement())
                .when(dataSecurity).assertCanManageDataSets();

        assertThatThrownBy(() -> service.save("assistant", definition(List.of("event_serach"))))
                .isInstanceOf(DatasetAccessDeniedException.class);
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
