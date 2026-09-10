// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.policy.NamingPolicyResolver;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.api.services.node.NodeUpdateService;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.repositories.governance.GovernanceTemplateRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.repositories.node.PolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Policy create is an adapter over the shared node pipeline, like the data set and function ones.
 *
 * <p>It used to be a hand-rolled copy of {@code ResourceService.create}, run once per item, which
 * is how it became the only create in the node family that no naming policy judged and the only
 * one that emitted an event per item. These pin the two things that must not drift back: the whole
 * batch goes through the pipeline in one call, and a {@code dataSetId} becomes an
 * {@code ENFORCED_ON} edge rather than a column on the policy node.
 */
@ExtendWith(MockitoExtension.class)
class PolicyServiceCreateTest {

    @Mock private NodeRepository nodeRepository;
    @Mock private ResourceService resourceService;
    @Mock private GovernanceTemplateRepository governanceTemplateRepo;
    @Mock private PolicyRepository policyRepository;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private DataSecurity dataSecurity;
    @Mock private NamingPolicyResolver namingPolicyResolver;
    @Mock private NodeUpdateService nodeUpdateService;
    @Mock private PolicyEnforcement policyEnforcement;

    @InjectMocks private PolicyService policyService;

    private static Policy body(String externalId, String name, Long dataSetId) {
        Policy p = new Policy();
        p.setExternalId(externalId);
        p.setName(name);
        p.setDataSetId(dataSetId);
        return p;
    }

    private static PolicyEntity entity(String externalId) {
        PolicyEntity e = new PolicyEntity();
        e.setId(ExternalIds.hash(externalId));
        e.setExternalId(externalId);
        e.setName(externalId);
        return e;
    }

    private void pipelineReturnsNodes(int count) throws Exception {
        var created = new GraphDataWrapper<NodeModel, EdgeProxy>();
        for (int i = 0; i < count; i++) {
            Policy echo = new Policy();
            echo.setId((long) (i + 1));
            created.getNodes().add(echo);
        }
        when(resourceService.create(any())).thenReturn(created);
    }

    @SuppressWarnings("unchecked")
    private GraphDataWrapper<NodeModel, RelForm> capturedRequest() throws Exception {
        ArgumentCaptor<GraphDataWrapper<NodeModel, RelForm>> cap = ArgumentCaptor.forClass(GraphDataWrapper.class);
        org.mockito.Mockito.verify(resourceService).create(cap.capture());
        return cap.getValue();
    }

    @Test
    void sendsTheWholeBatchThroughThePipelineInOneCall() throws Exception {
        pipelineReturnsNodes(2);
        when(policyRepository.findAllByExternalIdHashIn(anyList()))
                .thenReturn(List.of(entity("p_one"), entity("p_two")));

        DataWrapper<Policy> result = policyService.create(
                List.of(body("p_one", "One", null), body("p_two", "Two", null)));

        assertThat(result.getItems()).hasSize(2);
        assertThat(capturedRequest().getNodes()).hasSize(2);
    }

    /** The pipeline refuses a POLICY body naming a data set; the field means an edge, not a column. */
    @Test
    void turnsADataSetIdIntoAnEnforcedOnEdgeNotAColumn() throws Exception {
        pipelineReturnsNodes(1);
        when(policyRepository.findAllByExternalIdHashIn(anyList())).thenReturn(List.of(entity("p_one")));

        policyService.create(List.of(body("p_one", "One", 42L)));

        GraphDataWrapper<NodeModel, RelForm> sent = capturedRequest();
        assertThat(sent.getNodes()).allSatisfy(n -> assertThat(n.getDataSetId()).isNull());
        assertThat(sent.getRelations()).singleElement().satisfies(rel -> {
            assertThat(rel.getRelationshipType()).isEqualTo("ENFORCED_ON");
            assertThat(rel.getFromId()).isEqualTo(42L);
            assertThat(rel.getToExternalId()).isEqualTo("p_one");
        });
    }

    /** The POLICY type-label is what routes the body, and the DTO seeds it. */
    @Test
    void everyBodyCarriesThePolicyTypeLabel() throws Exception {
        pipelineReturnsNodes(1);
        when(policyRepository.findAllByExternalIdHashIn(anyList())).thenReturn(List.of(entity("p_one")));

        policyService.create(List.of(body("p_one", "One", null)));

        assertThat(capturedRequest().getNodes())
                .allSatisfy(n -> assertThat(n.getLabels()).contains("POLICY"));
    }

    /**
     * The generated fallback must survive the tenant's naming policy.
     *
     * <p>It did not have to while policy create had its own path. The shared pipeline judges every
     * create, and a raw UUIDv7's hyphens fail a SNAKE_CASE preset, so a tenant that had opted into
     * one could no longer create a policy without naming it themselves.
     */
    @Test
    void generatesAnExternalIdThatPassesASnakeCasePolicy() throws Exception {
        pipelineReturnsNodes(1);
        PolicyEntity created = new PolicyEntity();
        created.setId(9L);
        created.setName("One");
        when(policyRepository.findAllByExternalIdHashIn(anyList())).thenAnswer(inv -> {
            created.setExternalIdHash(((List<Long>) inv.getArgument(0)).getFirst());
            return List.of(created);
        });

        policyService.create(List.of(body(null, "One", null)));

        String generated = capturedRequest().getNodes().iterator().next().getExternalId();
        assertThat(generated).matches("[a-z0-9_]+");
    }

    /** A caller-supplied id is left exactly as sent, and judged on its merits like a rename is. */
    @Test
    void leavesACallerSuppliedExternalIdAlone() throws Exception {
        pipelineReturnsNodes(1);
        when(policyRepository.findAllByExternalIdHashIn(anyList())).thenReturn(List.of(entity("Policy-A-01")));

        policyService.create(List.of(body("Policy-A-01", "One", null)));

        assertThat(capturedRequest().getNodes().iterator().next().getExternalId())
                .isEqualTo("Policy-A-01");
    }

    /** A missing external id gets a generated one, and the edge must name that same value. */
    @Test
    void generatesAnExternalIdAndUsesItForTheEdge() throws Exception {
        pipelineReturnsNodes(1);
        PolicyEntity created = new PolicyEntity();
        created.setId(9L);
        created.setName("One");
        when(policyRepository.findAllByExternalIdHashIn(anyList())).thenAnswer(inv -> {
            created.setExternalIdHash(((List<Long>) inv.getArgument(0)).getFirst());
            return List.of(created);
        });

        policyService.create(List.of(body(null, "One", 42L)));

        GraphDataWrapper<NodeModel, RelForm> sent = capturedRequest();
        String generated = sent.getNodes().iterator().next().getExternalId();
        assertThat(generated).isNotBlank();
        assertThat(sent.getRelations().iterator().next().getToExternalId()).isEqualTo(generated);
    }

    /** Naming warnings the pipeline recorded must reach the caller, not be swallowed by re-wrapping. */
    @Test
    void carriesThePipelinesWarningsOut() throws Exception {
        var created = new GraphDataWrapper<NodeModel, EdgeProxy>();
        Policy echo = new Policy();
        echo.setId(1L);
        created.getNodes().add(echo);
        created.setWarnings(List.of(new ai.intellistream.datahub.models.policy.PolicyWarning(
                0, "p_one", "naming_snake_case", "Does not match naming policy.", "p_one")));
        when(resourceService.create(any())).thenReturn(created);
        when(policyRepository.findAllByExternalIdHashIn(anyList())).thenReturn(List.of(entity("p_one")));

        DataWrapper<Policy> result = policyService.create(List.of(body("p_one", "One", null)));

        assertThat(result.getWarnings()).hasSize(1);
    }
}
