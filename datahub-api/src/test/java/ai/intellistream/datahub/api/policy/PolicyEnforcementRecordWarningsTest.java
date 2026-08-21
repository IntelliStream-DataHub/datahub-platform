// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.policy;

import ai.intellistream.datahub.api.services.EventService;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.policy.PolicyDecision;
import ai.intellistream.datahub.models.policy.PolicyFinding;
import ai.intellistream.datahub.models.policy.PolicyFindingEvent;
import ai.intellistream.datahub.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Recording a warning as an event.
 *
 * <p>What is under test is the encoding and the failure behaviour, not the evaluator — the decision
 * that produced the warning is {@link NamingPolicyEvaluatorTest}'s job.
 */
class PolicyEnforcementRecordWarningsTest {

    private final EventService eventService = mock(EventService.class);
    private final PolicyEnforcement enforcement = new PolicyEnforcement(
            mock(NamingPolicyEvaluator.class), mock(NamingPolicyResolver.class), eventService);

    private static final PolicyFinding WARNING = new PolicyFinding(
            0, "PUMP-A-01", PolicyDecision.WARNING, "naming_default",
            "External id does not follow the naming policy.", "pump_a_01");

    @BeforeEach
    void setTenant() {
        TenantContext.setTenantId("tenant_a");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private List<EventModel> record(Map<String, PolicyEnforcement.WrittenEntity> written) {
        enforcement.recordWarnings(List.of(WARNING), written);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EventModel>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventService).createPlatformEvents(captor.capture());
        return captor.getValue();
    }

    /**
     * Record one finding through a fresh enforcement instance, so nothing can be carried between
     * calls in memory — an id that matches has to have been derived, not remembered.
     */
    private static EventModel recordWith(PolicyFinding finding,
                                         Map<String, PolicyEnforcement.WrittenEntity> written) {
        EventService events = mock(EventService.class);
        new PolicyEnforcement(mock(NamingPolicyEvaluator.class), mock(NamingPolicyResolver.class), events)
                .recordWarnings(List.of(finding), written);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EventModel>> captor = ArgumentCaptor.forClass(List.class);
        verify(events).createPlatformEvents(captor.capture());
        return captor.getValue().getFirst();
    }

    @Test
    void encodesTheFindingAsAnEvent() {
        EventModel event = record(Map.of("PUMP-A-01",
                new PolicyEnforcement.WrittenEntity(42L, 7L))).getFirst();

        assertThat(event.getType()).isEqualTo(PolicyFindingEvent.TYPE);
        assertThat(event.getSubType()).isEqualTo("naming_default");
        assertThat(event.getSource()).isEqualTo("datahub_policy_naming_default");
        assertThat(event.getStatus()).isEqualTo(PolicyFindingEvent.STATUS_OPEN);
        assertThat(event.getDescription()).isEqualTo("External id does not follow the naming policy.");
        assertThat(event.getExternalId()).isEqualTo("policy_finding_naming_default_42");

        // The entity is named by node id, and its data set is copied onto the finding so the
        // dataset ACL covers the finding on read without a policy-specific rule.
        assertThat(event.getRelatedResources()).singleElement()
                .satisfies(related -> {
                    assertThat(related.getId()).isEqualTo(42L);
                    // Deliberately no external id — see EventTransformer.toPolicyFindingEvent.
                    assertThat(related.getExternalId()).isNull();
                });
        assertThat(event.getDataSetId()).isEqualTo(7L);

        assertThat(event.getMetadata())
                .containsEntry(PolicyFindingEvent.META_OFFENDING_VALUE, "PUMP-A-01")
                .containsEntry(PolicyFindingEvent.META_SUGGESTION, "pump_a_01");
    }

    /**
     * Raising the same finding twice must write one event, not two. The id is derived from what the
     * event asserts, so an unchanged re-evaluation collapses onto the raise already stored — this is
     * what replaces the {@code UNIQUE (node_id, policy_name)} constraint the findings table carried.
     */
    @Test
    void anIdenticalRaiseCollapsesOntoTheStoredOne() {
        var written = Map.of("PUMP-A-01", new PolicyEnforcement.WrittenEntity(42L, 7L));

        String first = record(written).getFirst().getId();

        assertThat(recordWith(WARNING, written).getId()).isEqualTo(first);
    }

    /**
     * A raise for a <em>different</em> non-conforming value asserts something new about the entity,
     * so it is appended rather than collapsed — including when it follows a resolve, which is how a
     * finding reopens without any reopen rule existing.
     */
    @Test
    void aRaiseForANewOffendingValueIsADistinctEvent() {
        var written = Map.of(
                "PUMP-A-01", new PolicyEnforcement.WrittenEntity(42L, 7L),
                "PUMP-A-02", new PolicyEnforcement.WrittenEntity(42L, 7L));

        EventModel first = recordWith(WARNING, written);
        EventModel second = recordWith(new PolicyFinding(
                0, "PUMP-A-02", PolicyDecision.WARNING, "naming_default",
                "External id does not follow the naming policy.", "pump_a_02"), written);

        assertThat(second.getId()).isNotEqualTo(first.getId());

        // Same finding, though: both belong to one lifecycle, so they share the correlation key the
        // fold groups on and a resolve names.
        assertThat(second.getExternalId()).isEqualTo(first.getExternalId());
    }

    @Test
    void differentTenantsGetDifferentEventIdsForTheSameFinding() {
        var written = Map.of("PUMP-A-01", new PolicyEnforcement.WrittenEntity(42L, 7L));
        String tenantA = record(written).getFirst().getId();

        TenantContext.setTenantId("tenant_b");

        assertThat(recordWith(WARNING, written).getId()).isNotEqualTo(tenantA);
    }

    /**
     * An entity outside any data set is legal, and the finding about it must still be recorded —
     * this is the case that would have failed had the finding gone through the caller-facing create
     * path, where a null data set demands the write-all grant.
     */
    @Test
    void recordsFindingsForEntitiesWithNoDataSet() {
        EventModel event = record(Map.of("PUMP-A-01",
                new PolicyEnforcement.WrittenEntity(42L, null))).getFirst();

        assertThat(event.getDataSetId()).isNull();
    }

    /**
     * A finding that names no entity is unactionable — it would sit in the queue forever with
     * nothing to look at — so it is dropped rather than written.
     */
    @Test
    void skipsFindingsWhoseEntityWasNotWritten() {
        enforcement.recordWarnings(List.of(WARNING), Map.of());
        verifyNoInteractions(eventService);
    }

    /**
     * The entity is valid; the policy said so and the write has already been accepted. Letting the
     * note about it fail the write would turn an advisory warning into an outage, which is the exact
     * outcome {@code warn} mode exists to prevent.
     */
    @Test
    void aFailureToRecordDoesNotFailTheWrite() {
        doThrow(new RuntimeException("pulsar is down"))
                .when(eventService).createPlatformEvents(anyList());

        assertThatCode(() -> enforcement.recordWarnings(List.of(WARNING),
                Map.of("PUMP-A-01", new PolicyEnforcement.WrittenEntity(42L, 7L))))
                .doesNotThrowAnyException();
    }
}
