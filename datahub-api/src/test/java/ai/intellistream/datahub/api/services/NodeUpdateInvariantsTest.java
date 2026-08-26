// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.messaging.events.ResourceCudPublishEvent;
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
        @Mock private NamingPolicyResolver namingPolicyResolver;

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

        @Test
        @DisplayName("a policy update publishes exactly one CUD event")
        void aPolicyUpdatePublishesExactlyOneEvent() {
            TenantContext.setTenantId("tenant-1");
            PolicyEntity node = policy();
            when(policyRepository.findByIdOrExternalId(5L, null)).thenReturn(java.util.Optional.of(node));
            when(policyRepository.save(any())).thenReturn(node);

            policyService.updatePolicyNode(renaming());

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());
            ResourceCudPublishEvent event = (ResourceCudPublishEvent) captor.getValue();
            assertThat(event.message().getEventObject()).isEqualTo(EventObject.RESOURCE_AND_RELATION);
            // Documented workaround, not an oversight: the consumer's UPDATE branch ignores
            // `resources`, so the policy layer sends its idempotent CREATE upsert instead. Phase 6
            // (event carries resolved labels) is what lets this become an honest UPDATE.
            assertThat(event.message().getEventAction()).isEqualTo(EventAction.CREATE);
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

        @InjectMocks private TimeseriesService timeseriesService;

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
            doThrow(new AccessDeniedException("denied")).when(dataSecurity).assertCanWrite(any());

            assertThatThrownBy(() -> timeseriesService.updateTimeseries(renaming()))
                    .isInstanceOf(AccessDeniedException.class);

            verify(applicationEventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("a timeseries update publishes exactly one TIMESERIES event")
        void aTimeseriesUpdatePublishesExactlyOneEvent() throws Exception {
            TenantContext.setTenantId("tenant-1");
            when(timeseriesRepository.findAllByIdOrExternalId(any(), any())).thenReturn(List.of(entity()));
            when(policyEnforcement.check(anyList())).thenReturn(List.of());

            timeseriesService.updateTimeseries(renaming());

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());
            ResourceCudPublishEvent event = (ResourceCudPublishEvent) captor.getValue();
            assertThat(event.message().getEventObject()).isEqualTo(EventObject.TIMESERIES);
            assertThat(event.message().getEventAction()).isEqualTo(EventAction.UPDATE);
        }
    }
}
