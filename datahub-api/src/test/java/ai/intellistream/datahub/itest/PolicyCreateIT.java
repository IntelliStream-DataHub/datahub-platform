// SPDX-License-Identifier: AGPL-3.0-or-later
// Deliberately outside ai.intellistream.datahub.api: the application component-scans that package,
// so a nested @SpringBootConfiguration there would be picked up by every full-context test.
package ai.intellistream.datahub.itest;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.services.IngestQuotaService;
import ai.intellistream.datahub.api.services.PolicyService;
import ai.intellistream.datahub.api.services.ResourceService;
import ai.intellistream.datahub.api.services.TenantLimitsService;
import ai.intellistream.datahub.api.datasecurity.DatasetClosureService;
import ai.intellistream.datahub.api.edge.EdgeMapper;
import ai.intellistream.datahub.api.messaging.outbox.GraphOutbox;
import ai.intellistream.datahub.api.policy.NamingPolicyResolver;
import ai.intellistream.datahub.api.policy.PolicyEnforcement;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.services.node.NodeUpdateService;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.PolicyEntity;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.PolicyType;
import ai.intellistream.datahub.repositories.governance.GovernanceTemplateRepository;
import ai.intellistream.datahub.repositories.label.LabelRepository;
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
import ai.intellistream.datahub.testsupport.SharedPostgres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code POST /policies/create} against a real PostgreSQL container, migrated with the production
 * Flyway scripts: real repositories, real Hibernate, real rows.
 *
 * <p>Every policy-create test before this one mocked {@link ResourceService}, so none of them could
 * see that the body the adapter builds is one the pipeline refuses. {@code Policy.type} carried
 * {@code @NotNull} and the adapter has no type to put there, so create's opening
 * {@code validator.validate} rejected the batch and every request in every tenant answered 400
 * {@code nodes[0].type: must not be null} — including requests that did send a type, since the
 * adapter discards the caller's body and builds its own.
 *
 * <p>What a container buys over {@code PolicyCreateThroughTheRealPipelineTest}, which wires the same
 * services against mocked stores: the row is really written and read back. That covers the
 * single-table discriminator ({@code PolicyEntity} is {@code node_type = 6}), the {@code node_labels}
 * M2M the graph looks policies up by, the {@code external_id_hash} unique index the read-back
 * queries on, and the {@code NOT NULL} columns a Hibernate-generated schema would not reproduce.
 *
 * <p>Mocked deliberately: the ACL, the naming policy, quotas and the graph outbox. Each is a
 * decision taken above the database on state this test does not stand up (a tenant, a JWT, a
 * configured policy), and none of them is where the create broke.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = PolicyCreateIT.JpaConfig.class)
class PolicyCreateIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("policy_create_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "ai.intellistream.datahub.repositories")
    @EnableTransactionManagement
    @EntityScan(basePackageClasses = NodeEntity.class)
    static class JpaConfig {
    }

    @Autowired private NodeRepository nodeRepository;
    @Autowired private PolicyRepository policyRepository;
    @Autowired private EdgeRepository edgeRepository;
    @Autowired private RelationshipTypeRepository relationshipTypeRepository;
    @Autowired private LabelRepository labelRepository;
    @Autowired private DataSetRepository dataSetRepository;
    @Autowired private PlatformTransactionManager txManager;

    @PersistenceContext private EntityManager em;

    private PolicyService policyService;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        LabelService labelService = new LabelService(labelRepository, validator, txManager);
        RelationshipTypeService relationshipTypeService =
                new RelationshipTypeService(relationshipTypeRepository, txManager);

        PolicyEnforcement policyEnforcement = mock(PolicyEnforcement.class);
        when(policyEnforcement.check(any())).thenReturn(List.of());

        TenantLimitsService tenantLimits = mock(TenantLimitsService.class);
        when(tenantLimits.current()).thenReturn(null);   // no ceiling configured

        ResourceService resourceService = new ResourceService(
                em,
                nodeRepository,
                new NodeService(labelService, dataSetRepository),
                edgeRepository,
                relationshipTypeRepository,
                relationshipTypeService,
                mock(ApplicationEventPublisher.class),
                mock(GraphOutbox.class),
                mock(Neo4JService.class),
                mock(DataSecurity.class),
                mock(SubscriptionRepository.class),
                validator,
                policyEnforcement,
                mock(DatasetClosureService.class),
                mock(IngestQuotaService.class),
                tenantLimits,
                new EdgeMapper(nodeRepository, relationshipTypeRepository, relationshipTypeService),
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

    /** The payload the console's policy form sends: no {@code type}, no {@code templateId}. */
    @Test
    void writesAPolicyRowFromThePayloadTheConsoleSends() throws Exception {
        Policy form = new Policy();
        form.setName("Read only after 90 days");
        form.setExternalId("plant_oslo_read_only");
        form.setDescription("Freeze the dataset once it is 90 days old.");
        form.setMetadata(Map.of("kind", "LIFECYCLE", "lifecycleAction", "FREEZE"));

        DataWrapper<Policy> created = policyService.create(List.of(form));

        assertThat(created.getItems()).singleElement().satisfies(policy -> {
            assertThat(policy.getId()).isNotNull();
            assertThat(policy.getExternalId()).isEqualTo("plant_oslo_read_only");
        });

        // Read it back as a row, not from the echo: a policy the API answered 201 for but did not
        // store would pass every assertion above.
        em.flush();
        em.clear();
        Long id = created.getItems().iterator().next().getId();
        assertThat(nodeRepository.findById(id)).get().satisfies(node -> {
            assertThat(node).isInstanceOf(PolicyEntity.class);
            assertThat(node.getName()).isEqualTo("Read only after 90 days");
            assertThat(node.getDescription()).isEqualTo("Freeze the dataset once it is 90 days old.");
            assertThat(node.getMetadata()).containsEntry("kind", "LIFECYCLE");
            // The graph keys off the label rows, not the denormalised string; a writer that sets
            // only the string produces a policy that matches no label filter.
            assertThat(node.getLabelEntities()).extracting("name").contains("POLICY");
            assertThat(node.getLabels()).contains("POLICY");
        });
    }

    /** A caller who does send a type is not punished for it. */
    @Test
    void writesAPolicyRowWhenTheBodyCarriesAType() throws Exception {
        Policy form = new Policy();
        form.setName("IS_WRITE_PROTECTED");
        form.setExternalId("plant_oslo_write_protected");
        form.setType(PolicyType.IS_WRITE_PROTECTED);

        DataWrapper<Policy> created = policyService.create(List.of(form));

        assertThat(created.getItems()).singleElement().satisfies(policy ->
                assertThat(policy.getType()).isEqualTo(PolicyType.IS_WRITE_PROTECTED));
    }

    /** No external id: the generated one has to survive the unique index and the read-back. */
    @Test
    void writesAPolicyRowWithAGeneratedExternalId() throws Exception {
        Policy form = new Policy();
        form.setName("Read only");

        DataWrapper<Policy> created = policyService.create(List.of(form));

        assertThat(created.getItems()).singleElement().satisfies(policy -> {
            assertThat(policy.getId()).isNotNull();
            assertThat(policy.getExternalId()).matches("[a-z0-9_]+");
        });
    }

    /** A whole batch in one create, the case the per-item loop this replaced could not express. */
    @Test
    void writesEveryPolicyInABatch() throws Exception {
        Policy one = new Policy();
        one.setName("One");
        one.setExternalId("batch_policy_one");
        Policy two = new Policy();
        two.setName("Two");
        two.setExternalId("batch_policy_two");

        assertThat(policyService.create(List.of(one, two)).getItems()).hasSize(2);

        em.flush();
        em.clear();
        assertThat(policyRepository.findAll())
                .extracting(NodeEntity::getExternalId)
                .contains("batch_policy_one", "batch_policy_two");
    }

    /**
     * {@code dataSetId} means an {@code ENFORCED_ON} edge, never a column: a policy node is an
     * orphan by construction, and the pipeline refuses a POLICY body naming a data set.
     */
    @Test
    void attachesAPolicyToADataSetWithAnEdgeAndNotAColumn() throws Exception {
        DatasetEntity dataset = new DatasetEntity();
        dataset.setExternalId("plant_oslo");
        dataset.setName("Plant Oslo");
        dataset.setLabels("DATASET");   // @NotEmpty on the entity; every node carries its type-label
        em.persist(dataset);
        em.flush();

        Policy form = new Policy();
        form.setName("Read only");
        form.setExternalId("plant_oslo_enforced");
        form.setDataSetId(dataset.getId());

        DataWrapper<Policy> created = policyService.create(List.of(form));

        em.flush();
        em.clear();
        Long id = created.getItems().iterator().next().getId();
        assertThat(nodeRepository.findById(id)).get().satisfies(node ->
                assertThat(node.getDataSet()).isNull());
        assertThat(edgeRepository.findAll())
                .anySatisfy(edge -> assertThat(edge.getRelationshipType().getName()).isEqualTo("ENFORCED_ON"));
    }
}
