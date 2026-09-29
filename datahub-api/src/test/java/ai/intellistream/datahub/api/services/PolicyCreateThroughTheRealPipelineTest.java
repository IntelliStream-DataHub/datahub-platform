// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.messaging.outbox.GraphOutbox;
import ai.intellistream.datahub.api.policy.NamingPolicyResolver;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.edge.EdgeMapper;
import ai.intellistream.datahub.api.services.node.NodeUpdateService;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.repositories.governance.GovernanceTemplateRepository;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.EdgeRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.repositories.node.PolicyRepository;
import ai.intellistream.datahub.repositories.node.RelationshipTypeRepository;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.services.LabelService;
import ai.intellistream.datahub.services.Neo4JService;
import ai.intellistream.datahub.services.NodeService;
import ai.intellistream.datahub.services.RelationshipTypeService;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A policy create the way the request actually travels: {@code PolicyService} over the <em>real</em>
 * {@link ResourceService}, with the real bean {@link Validator} and the real {@link NodeService}
 * that builds the entity. Only the stores below them are mocked.
 *
 * <p>This is the seam every other policy-create test skips. They mock {@code ResourceService.create}
 * and assert the shape handed to it, so they could not see that the shape itself was one the
 * pipeline refuses: {@code Policy.type} carried {@code @NotNull}, the adapter has no type to put
 * there, and {@code create}'s first act is to validate a {@code @Valid} node collection. Every
 * {@code POST /policies/create} in every tenant answered 400 {@code nodes[0].type: must not be null}
 * — including requests that <em>did</em> send a type, since the adapter builds its own body.
 *
 * <p>Held at this level rather than against a container because every way this create can be
 * refused — bean validation, the ACL gates, the external-id and data-set guards, the naming policy,
 * and entity mapping — happens before a row is written. A database would add coverage of the write,
 * which is not where it broke.
 */
class PolicyCreateThroughTheRealPipelineTest {

    private PolicyService policyService;
    private NodeRepository nodeRepository;
    private PolicyRepository policyRepository;
    private ValidatorFactory validatorFactory;

    @BeforeEach
    void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();

        LabelService labelService = mock(LabelService.class);
        // Find-or-create, the same contract the real one honours: a name in, a Label out.
        when(labelService.findAllAndCreateFromNames(anyList())).thenAnswer(inv -> {
            List<Label> labels = new ArrayList<>();
            for (String name : inv.<List<String>>getArgument(0)) {
                Label label = new Label();
                label.setName(name);
                labels.add(label);
            }
            return labels;
        });

        nodeRepository = mock(NodeRepository.class);
        // Server-assigned ids, so the echo and the outbox have something to name.
        AtomicLong ids = new AtomicLong(1);
        when(nodeRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<NodeEntity> saved = inv.getArgument(0);
            saved.forEach(node -> node.setId(ids.getAndIncrement()));
            return saved;
        });

        policyRepository = mock(PolicyRepository.class);

        ResourceService resourceService = new ResourceService(
                mock(EntityManager.class),
                nodeRepository,
                new NodeService(labelService, mock(DataSetRepository.class)),
                mock(EdgeRepository.class),
                mock(RelationshipTypeRepository.class),
                mock(RelationshipTypeService.class),
                mock(ApplicationEventPublisher.class),
                mock(GraphOutbox.class),
                mock(Neo4JService.class),
                mock(DataSecurity.class),
                mock(SubscriptionRepository.class),
                validatorFactory.getValidator(),
                mock(PolicyEnforcement.class),
                mock(DatasetClosureService.class),
                mock(IngestQuotaService.class),
                mock(TenantLimitsService.class),
                mock(EdgeMapper.class),
                mock(NodeUpdateService.class),
                mock(NamingPolicyResolver.class));

        policyService = new PolicyService(
                nodeRepository,
                resourceService,
                mock(GovernanceTemplateRepository.class),
                policyRepository,
                mock(ApplicationEventPublisher.class),
                mock(GraphOutbox.class),
                mock(DataSecurity.class),
                mock(NamingPolicyResolver.class),
                mock(NodeUpdateService.class),
                mock(PolicyEnforcement.class));
    }

    /** Read-back is by external-id hash, so echo whatever the pipeline just saved under it. */
    private void readBackWhateverWasSaved() {
        when(policyRepository.findAllByExternalIdHashIn(anyList())).thenAnswer(inv -> {
            List<Long> hashes = inv.getArgument(0);
            List<PolicyEntity> found = new ArrayList<>();
            for (Long hash : hashes) {
                PolicyEntity e = new PolicyEntity();
                e.setId(hash);
                e.setName("One");
                e.setExternalIdHash(hash);
                found.add(e);
            }
            return found;
        });
    }

    /** The payload the console's policy form sends: no {@code type}, no {@code templateId}. */
    @Test
    void createsAPolicyFromThePayloadTheConsoleSends() throws Exception {
        readBackWhateverWasSaved();

        Policy form = new Policy();
        form.setName("Read only after 90 days");
        form.setExternalId("plant_oslo_read_only");
        form.setDescription("Freeze the dataset once it is 90 days old.");
        form.setMetadata(Map.of("kind", "LIFECYCLE", "lifecycleAction", "FREEZE"));

        DataWrapper<Policy> created = policyService.create(List.of(form));

        assertThat(created.getItems()).hasSize(1);
    }

    /** The entity the pipeline built must be a policy, labelled the way the graph looks it up. */
    @Test
    void buildsAPolicyEntityCarryingThePolicyLabel() throws Exception {
        readBackWhateverWasSaved();

        Policy form = new Policy();
        form.setName("Read only");
        form.setExternalId("plant_oslo_read_only");

        policyService.create(List.of(form));

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(nodeRepository).saveAll(captor.capture());
        assertThat((List<NodeEntity>) captor.getValue()).singleElement().satisfies(node -> {
            assertThat(node).isInstanceOf(PolicyEntity.class);
            assertThat(node.getLabels()).contains("POLICY");
            assertThat(node.getExternalId()).isEqualTo("plant_oslo_read_only");
            // A policy node is an orphan by construction; the pipeline refuses one that is not.
            assertThat(node.getDataSet()).isNull();
        });
    }

    /** A caller who does send a type is not punished for it either. */
    @Test
    void acceptsABodyThatCarriesAType() throws Exception {
        readBackWhateverWasSaved();

        Policy form = new Policy();
        form.setName("IS_WRITE_PROTECTED");
        form.setExternalId("plant_oslo_write_protected");
        form.setType(ai.intellistream.datahub.models.PolicyType.IS_WRITE_PROTECTED);

        assertThat(policyService.create(List.of(form)).getItems()).hasSize(1);
    }

    /** No external id at all: the pipeline must accept the one the adapter generates. */
    @Test
    void createsAPolicyWithNoExternalIdSupplied() throws Exception {
        readBackWhateverWasSaved();

        Policy form = new Policy();
        form.setName("Read only");

        assertThat(policyService.create(List.of(form)).getItems()).hasSize(1);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        validatorFactory.close();
    }
}
