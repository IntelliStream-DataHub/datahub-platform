// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.messaging.outbox.GraphOutbox;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.api.policy.NamingPolicyResolver;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import ai.intellistream.datahub.models.forms.UpdatePolicyForm;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventObject;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.models.forms.DataSetForm;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.repositories.node.PolicyRepository;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.timeseries.UpdateTimeseries;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>The M3 safety net.</b> Every node update, whichever service owns it today, must do the same
 * cross-cutting things: authorize the caller, and publish exactly one CUD event so the derived
 * stores (Neo4j, and anything else consuming the topic) see the change once and only once. Those
 * steps are currently re-implemented in three places — {@code ResourceService.update},
 * {@code PolicyService.updatePolicyNode} and {@code TimeseriesService.updateTimeseries} — which is
 * precisely why NODE_UPDATE_REFACTOR.md wants one pipeline with per-type behaviour injected.
 *
 * <p>This class pins the invariants <em>before</em> that consolidation starts, so a phase that
 * quietly drops an ACL check or stops emitting an event fails here rather than in production. It
 * must stay green through every phase; once the engine owns these steps, these tests should be
 * re-pointed at it rather than deleted.
 *
 * <p>Deliberately not asserted here, because it is a known divergence the pipeline is meant to
 * settle rather than a rule to freeze: {@code ResourceService.update} and
 * {@code TimeseriesService.updateTimeseries} judge a rename against the naming policy
 * ({@code policyEnforcement.check}), while {@code PolicyService.updatePolicyNode} renames a policy
 * without consulting it. See the class javadoc note in that service.
 */
class NodeUpdateInvariantsTest {

    /**
     * The dataset path converged when the pipeline was extracted: {@code DataSetService} adapts
     * its typed form into the shared command and delegates, so it inherits the ACL, the naming
     * judgement, the type-label guard and the single event rather than restating them. This pins
     * the delegation itself — the failure it guards against is someone giving datasets their own
     * update path again, which would silently reacquire all four problems.
     */
    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class Datasets {

        @Mock private NodeRepository nodeRepository;
        @Mock private ResourceService resourceService;
        @Mock private DataSetRepository dataSetRepository;
        @Mock private ApplicationEventPublisher applicationEventPublisher;
        @Mock private GraphOutbox graphOutbox;

        @InjectMocks private DataSetService dataSetService;

        @Test
        @DisplayName("a dataset update delegates to the shared pipeline rather than writing its own")
        void aDatasetUpdateDelegates() throws Exception {
            DatasetEntity entity = new DatasetEntity();
            entity.setId(9L);
            entity.setExternalId("plant_data");
            entity.setLabels("DATASET");

            DataSetForm form = new DataSetForm();
            form.setId(9L);
            form.setUpdate(new ai.intellistream.datahub.models.forms.DataSetFields());
            form.getUpdate().getName().set("Plant data (renamed)");

            dataSetService.validateAndUpdate(entity, form);

            // The rename reaches the shared pipeline as the canonical command...
            ArgumentCaptor<GraphDataWrapper<UpdateResourceForm, UpdateRelForm>> captor =
                    ArgumentCaptor.forClass(GraphDataWrapper.class);
            verify(resourceService).update(captor.capture());
            UpdateResourceForm command = captor.getValue().getNodes().iterator().next();
            assertThat(command.getUpdate().getName().getSet()).isEqualTo("Plant data (renamed)");

            // ...and nothing is persisted or published here, which is what makes the pipeline the
            // single owner of those steps for datasets too.
            verify(dataSetRepository, never()).save(any());
            verify(applicationEventPublisher, never()).publishEvent(any());
        }
    }

    /** The policy path: gated by the manage grant, and upserted to the graph exactly once. */
    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class Policies {

        @Mock private PolicyRepository policyRepository;
        @Mock private DataSecurity dataSecurity;
        @Mock private ApplicationEventPublisher applicationEventPublisher;
        @Mock private GraphOutbox graphOutbox;
        @Mock private NamingPolicyResolver namingPolicyResolver;
        @Mock private ai.intellistream.datahub.api.services.node.NodeUpdateService nodeUpdateService;

        @InjectMocks private PolicyService policyService;

        @AfterEach
        void clearTenant() {
            TenantContext.clear();
        }

        private static PolicyEntity policy() {
            PolicyEntity node = new PolicyEntity();
            node.setId(5L);
            node.setName("IS_WRITE_PROTECTED");
            node.setExternalId("is_write_protected");
            node.setLabels("POLICY");
            return node;
        }

        private static UpdatePolicyForm renaming() {
            UpdatePolicyForm form = new UpdatePolicyForm();
            form.setId(5L);
            form.getUpdate().getName().set("IS_READ_PROTECTED");
            return form;
        }

        @Test
        @DisplayName("a policy update is refused without the manage grant, and nothing is published")
        void aPolicyUpdateEnforcesAcl() {
            TenantContext.setTenantId("tenant-1");
            doThrow(new AccessDeniedException("denied")).when(dataSecurity).assertCanManageDataSets();

            assertThatThrownBy(() -> policyService.updatePolicyNode(renaming()))
                    .isInstanceOf(AccessDeniedException.class);

            verify(policyRepository, never()).save(any());
            verify(applicationEventPublisher, never()).publishEvent(any());
        }

        /**
         * The divergence Phase 0 recorded, now closed. A policy rename used to be the one rename
         * in the system no naming convention was allowed to judge: the resource and timeseries
         * paths both ran {@code policyEnforcement.check}, and the policy path did not. Folding the
         * shared half onto the pipeline is what fixes it, because judging is one of the stages.
         */
        @Test
        @DisplayName("a policy rename is judged by the naming policy")
        void aPolicyRenameIsJudged() {
            TenantContext.setTenantId("tenant-1");
            PolicyEntity node = policy();
            when(policyRepository.findByIdOrExternalId(5L, null)).thenReturn(java.util.Optional.of(node));
            when(policyRepository.save(any())).thenReturn(node);
            when(nodeUpdateService.authorize(any(), any())).thenAnswer(inv ->
                    new ai.intellistream.datahub.api.services.node.NodeUpdateService.Target(
                            inv.getArgument(0), inv.getArgument(1)));

            UpdatePolicyForm renamingExternalId = new UpdatePolicyForm();
            renamingExternalId.setId(5L);
            renamingExternalId.getUpdate().getExternalId().set("is_read_protected");

            policyService.updatePolicyNode(renamingExternalId);

            // The rename reaches the naming judgement, carrying the new external id.
            ArgumentCaptor<java.util.List<ai.intellistream.datahub.api.services.node.NodeUpdateService.Target>> captor =
                    ArgumentCaptor.forClass(java.util.List.class);
            verify(nodeUpdateService).judgeNaming(captor.capture());
            var judged = captor.getValue().get(0);
            assertThat(judged.form().getUpdate().getExternalId().getSet()).isEqualTo("is_read_protected");
        }

        @Test
        @DisplayName("a policy update publishes exactly one CUD event")
        void aPolicyUpdatePublishesExactlyOneEvent() {
            TenantContext.setTenantId("tenant-1");
            PolicyEntity node = policy();
            when(policyRepository.findByIdOrExternalId(5L, null)).thenReturn(java.util.Optional.of(node));
            when(policyRepository.save(any())).thenReturn(node);
            // The shared half runs through the pipeline; here it only has to hand a target back.
            when(nodeUpdateService.authorize(any(), any())).thenAnswer(inv ->
                    new ai.intellistream.datahub.api.services.node.NodeUpdateService.Target(
                            inv.getArgument(0), inv.getArgument(1)));

            policyService.updatePolicyNode(renaming());

            // Exactly one queued graph mutation. The CREATE-for-an-update workaround this used to
            // assert is gone with the message: there is no event action any more, and queueUpsert
            // is a genuine upsert, so the policy path no longer has to lie about what it did.
            verify(graphOutbox).queueUpsert(anyCollection(), anyCollection());
        }
    }

    /** The timeseries path: gated per-node on its dataset, and published once as TIMESERIES. */
    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class Timeseries {

        @Mock private TimeseriesRepository timeseriesRepository;
        @Mock private NodeRepository nodeRepository;
        @Mock private DataSecurity dataSecurity;
        @Mock private PolicyEnforcement policyEnforcement;
        @Mock private ApplicationEventPublisher applicationEventPublisher;
        @Mock private GraphOutbox graphOutbox;
        @Mock private ai.intellistream.datahub.api.services.node.NodeUpdateService nodeUpdateService;

        @InjectMocks private TimeseriesService timeseriesService;

        /** The pipeline hands a target back; the engine's own ACL is exercised by its own tests. */
        private void pipelineAuthorizes() {
            when(nodeUpdateService.authorize(any(), any())).thenAnswer(inv ->
                    new ai.intellistream.datahub.api.services.node.NodeUpdateService.Target(
                            inv.getArgument(0), inv.getArgument(1)));
        }

        @AfterEach
        void clearTenant() {
            TenantContext.clear();
        }

        private static TimeseriesEntity entity() {
            TimeseriesEntity ts = new TimeseriesEntity();
            ts.setId(1L);
            ts.setExternalId("engine_temp");
            ts.setName("Engine Temp");
            ts.setLabels("TIMESERIES");
            ts.setValueType(new TimeseriesValueType(7));
            return ts;
        }

        private static ai.intellistream.datahub.api.responses.DataWrapper<UpdateTimeseries> renaming() {
            UpdateTimeseries form = new UpdateTimeseries();
            form.setExternalId("engine_temp");
            form.getUpdate().getName().set("Engine Temperature");
            var wrapper = new ai.intellistream.datahub.api.responses.DataWrapper<UpdateTimeseries>();
            wrapper.getItems().add(form);
            return wrapper;
        }

        @Test
        @DisplayName("a timeseries update is refused without dataset write, and nothing is published")
        void aTimeseriesUpdateEnforcesAcl() throws Exception {
            TenantContext.setTenantId("tenant-1");
            when(timeseriesRepository.findAllByIdOrExternalId(any(), any())).thenReturn(List.of(entity()));
            when(policyEnforcement.check(anyList())).thenReturn(List.of());
            // Authorization moved into the pipeline in Phase 5, so what this path must guarantee
            // is that it routes through it and propagates the denial. That the pipeline actually
            // enforces the grant is pinned against a real engine by ResourceServiceNodeTypeAclTest.
            doThrow(new AccessDeniedException("denied")).when(nodeUpdateService).authorize(any(), any());

            assertThatThrownBy(() -> timeseriesService.updateTimeseries(renaming()))
                    .isInstanceOf(AccessDeniedException.class);

            verify(applicationEventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("a timeseries update queues exactly one graph mutation")
        void aTimeseriesUpdateQueuesExactlyOneGraphMutation() throws Exception {
            TenantContext.setTenantId("tenant-1");
            when(timeseriesRepository.findAllByIdOrExternalId(any(), any())).thenReturn(List.of(entity()));
            when(policyEnforcement.check(anyList())).thenReturn(List.of());
            pipelineAuthorizes();

            timeseriesService.updateTimeseries(renaming());

            // The timeseries path queues by id (its entities are already saved), so it is the
            // queueUpsertIds variant here. The invariant is unchanged: one graph mutation per
            // update, never one per node.
            verify(graphOutbox).queueUpsertIds(anyCollection(), anyCollection());
        }
    }
}
