// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.repositories.label.LabelRepository;
import ai.intellistream.datahub.repositories.node.DataSetRepository;
import ai.intellistream.datahub.repositories.node.NodeRepository;
import ai.intellistream.datahub.testsupport.SharedPostgres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A node's labels live in two places, and both have to be written.
 *
 * <p>{@code node.labels} is a denormalised comma-separated string, which is what reads report.
 * {@code node_labels} is the join table, which is what filtering matches on — via {@code label.hash},
 * the indexed column. A node with only the first is one whose labels you can see and cannot search
 * for, and the failure is an empty result: indistinguishable from "nothing is tagged that way".
 *
 * <p>Reported against datasets specifically, so this exercises the create path a dataset actually
 * takes — {@code DataSetTransformer.toResource} hands a Resource labelled DATASET to
 * {@code NodeService.createFromResource}, which is the same entry point every node type uses — and
 * asserts the join row exists afterwards. Every type-label is covered, because the same gap would
 * apply to any of them.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = DatasetLabelJoinRowIT.JpaConfig.class)
class DatasetLabelJoinRowIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("dataset_label_join_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = {NodeRepository.class, LabelRepository.class})
    @EntityScan(basePackageClasses = NodeEntity.class)
    static class JpaConfig {

        @Bean
        Validator validator() {
            return Validation.buildDefaultValidatorFactory().getValidator();
        }

        @Bean
        LabelService labelService(LabelRepository labelRepository, Validator validator,
                                  PlatformTransactionManager transactionManager) {
            return new LabelService(labelRepository, validator, transactionManager);
        }

        @Bean
        NodeService nodeService(LabelService labelService, DataSetRepository dataSetRepository) {
            return new NodeService(labelService, dataSetRepository);
        }
    }

    @Autowired private NodeService nodeService;
    @Autowired private NodeRepository nodeRepository;

    @PersistenceContext private EntityManager em;

    /** The Resource a dataset create builds, exactly as DataSetTransformer.toResource does. */
    private static Resource datasetResource(String externalId) {
        Resource r = new Resource();
        r.setExternalId(externalId);
        r.setName(externalId);
        r.setLabels(List.of("DATASET"));
        return r;
    }

    private long joinRowCount(long nodeId) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM node_labels WHERE node_id = :id")
                .setParameter("id", nodeId)
                .getSingleResult()).longValue();
    }

    @Test
    @DisplayName("creating a dataset writes both the label string and the join row")
    void datasetCreateWritesTheLabelJoinRow() {
        NodeEntity built = nodeService.createFromResource(datasetResource("pytest_lbl_dataset"));
        NodeEntity saved = nodeRepository.save(built);
        nodeRepository.flush();
        em.clear();

        assertThat(saved).isInstanceOf(DatasetEntity.class);

        NodeEntity reread = em.find(NodeEntity.class, saved.getId());
        assertThat(reread.getLabels()).as("the denormalised string, which reads report").contains("DATASET");
        assertThat(joinRowCount(saved.getId()))
                .as("the join row, which filtering matches on — without it the label is visible "
                        + "but unsearchable, and the filter returns an empty result")
                .isEqualTo(1);
        assertThat(reread.getLabelEntities()).extracting("name").containsExactly("DATASET");
    }

    /** TypeLabels.ALL also covers ASSET, POLICY and FUNCTION; the same gap would apply to each. */
    @Test
    @DisplayName("every type-label writes its join row, not just the one that was reported")
    void everyTypeLabelWritesItsJoinRow() {
        for (String typeLabel : List.of("ASSET", "DATASET", "POLICY", "FUNCTION")) {
            Resource r = new Resource();
            r.setExternalId("pytest_lbl_" + typeLabel.toLowerCase());
            r.setName(r.getExternalId());
            r.setLabels(List.of(typeLabel));

            NodeEntity saved = nodeRepository.save(nodeService.createFromResource(r));
            nodeRepository.flush();

            assertThat(joinRowCount(saved.getId()))
                    .as("%s node has no node_labels row", typeLabel)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("caller labels are written alongside the type-label")
    void callerLabelsAreWrittenToo() {
        Resource r = new Resource();
        r.setExternalId("pytest_lbl_extra");
        r.setName("extra");
        r.setLabels(List.of("DATASET", "PLANT_A"));

        NodeEntity saved = nodeRepository.save(nodeService.createFromResource(r));
        nodeRepository.flush();
        em.clear();

        assertThat(joinRowCount(saved.getId())).isEqualTo(2);
    }
}
