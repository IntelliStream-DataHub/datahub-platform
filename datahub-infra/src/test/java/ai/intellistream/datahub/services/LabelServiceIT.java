// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.testsupport.SharedPostgres;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.errors.EntityInUseException;
import ai.intellistream.datahub.errors.InvalidResourceException;
import ai.intellistream.datahub.helpers.updates.UpdateListField;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.ResourceEntity;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.repositories.label.LabelRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for {@link LabelService} against a real PostgreSQL container (Testcontainers,
 * migrated with the production Flyway scripts). These exercise the paths that mocked-repository unit tests can't:
 * real persistence + Hibernate merge, the {@code UNIQUE(hash)} constraint and hash-based lookups,
 * the {@code node_labels} M2M (labelEntities / Label.nodes), and the full round-trip of applying a
 * label update's set/add/remove — including the null / empty / combined cases — to a node.
 *
 * <p>{@link LabelService#findAllAndCreateFromNames} commits in a nested REQUIRES_NEW transaction, so
 * those tests use unique names and assert on specific rows rather than the outer test rollback.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = LabelServiceIT.JpaConfig.class)
class LabelServiceIT {


    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("label_service_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = LabelRepository.class)
    @EntityScan(basePackageClasses = Label.class)
    static class JpaConfig {
    }

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @PersistenceContext
    private EntityManager em;

    private LabelService labelService;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        // Constructed like the app wires it; findAllAndCreateFromNames still gets a real
        // REQUIRES_NEW template so its concurrency/commit behaviour is exercised for real.
        labelService = new LabelService(labelRepository, validator, txManager);
    }

    // --- helpers -------------------------------------------------------------------------------

    private Label persistLabel(String name) {
        Label label = new Label();
        label.setName(name);         // canonicalises + derives hash
        label.setColor("#123456");
        em.persist(label);
        return label;
    }

    private void seedLabels(String... names) {
        for (String n : names) {
            persistLabel(n);
        }
        em.flush();
    }

    private DatasetEntity persistDataset(String externalId, String labelsCsv) {
        DatasetEntity ds = new DatasetEntity();
        ds.setExternalId(externalId);
        ds.setName("Node " + externalId);
        ds.setLabels(labelsCsv);
        ds.setLabelEntities(new ArrayList<>(labelRepository.findAllByNameIn(Set.of(labelsCsv.split(",")))));
        em.persist(ds);
        em.flush();
        return ds;
    }

    /** Apply a label update the way ResourceService.update does (string + M2M), persist, reload. */
    private DatasetEntity applyAndReload(DatasetEntity node, UpdateListField update) {
        Long id = node.getId();
        labelService.resolveLabelUpdate(node, update).ifPresent(names -> {
            node.setLabels(String.join(",", names));
            node.setLabelEntities(new ArrayList<>(labelRepository.findAllByNameIn(Set.copyOf(names))));
        });
        em.flush();
        em.clear();
        return em.find(DatasetEntity.class, id);
    }

    private static Set<String> labelString(NodeEntity n) {
        return Set.of(n.getLabels().split(","));
    }

    private static Set<String> m2mNames(NodeEntity n) {
        return n.getLabelEntities().stream().map(Label::getName).collect(Collectors.toSet());
    }

    private static DataWrapper<IdCollection> byId(Long id) {
        var form = new DataWrapper<IdCollection>();
        form.getItems().add(IdCollection.createFromId(id));
        return form;
    }

    private static DataWrapper<IdCollection> byExternalId(String externalId) {
        var form = new DataWrapper<IdCollection>();
        form.getItems().add(IdCollection.createFromExternalId(externalId));
        return form;
    }

    // --- findAllAndCreateFromNames (real create / reuse / reject) -------------------------------

    @Test
    @DisplayName("creates a new label row for an unseen name")
    void findAllAndCreateFromNames_createsNewLabel() {
        List<Label> result = labelService.findAllAndCreateFromNames(List.of("it create new"));

        assertThat(result).extracting(Label::getName).containsExactly("IT_CREATE_NEW");
        assertThat(labelRepository.findAllByNameIn(Set.of("IT_CREATE_NEW"))).hasSize(1);
    }

    @Test
    @DisplayName("reuses an existing label for case/snake variants — no duplicate row, no 409")
    void findAllAndCreateFromNames_reusesExistingForVariants() {
        labelService.findAllAndCreateFromNames(List.of("It Reuse")); // -> IT_REUSE

        List<Label> again = labelService.findAllAndCreateFromNames(List.of("it reuse", "IT_REUSE"));

        assertThat(again).extracting(Label::getName).containsOnly("IT_REUSE");
        assertThat(labelRepository.findAllByNameIn(Set.of("IT_REUSE"))).hasSize(1); // reused, not re-inserted
    }

    @Test
    @DisplayName("rejects a name that canonicalises to an invalid Label.name instead of a 500")
    void findAllAndCreateFromNames_rejectsInvalidName() {
        // "1" -> toSnakeUpperCased strips leading digits -> "" (fails @Size(2,512))
        assertThrows(InvalidResourceException.class,
                () -> labelService.findAllAndCreateFromNames(List.of("IT_VALID_ONE", "1")));
        // nothing persisted — the reject happens before any save
        assertThat(labelRepository.findAllByNameIn(Set.of("IT_VALID_ONE"))).isEmpty();
    }

    // --- delete (id / external-id hash / in-use) -----------------------------------------------

    @Test
    @DisplayName("delete by id removes the row")
    void delete_byId_removesRow() {
        Label label = persistLabel("SENSOR");
        em.flush();

        labelService.delete(byId(label.getId()));
        em.clear();

        assertThat(labelRepository.findById(label.getId())).isEmpty();
    }

    @Test
    @DisplayName("delete by external id resolves via the hash even for differently-cased input")
    void delete_byExternalId_mixedCase_resolvesViaHash() {
        Label label = persistLabel("SENSOR"); // hash = xx3("SENSOR")
        em.flush();

        labelService.delete(byExternalId("sensor")); // lower-case; must still match the stored hash
        em.clear();

        assertThat(labelRepository.findById(label.getId())).isEmpty();
    }

    @Test
    @DisplayName("delete of a label still referenced by a node is rejected and keeps the row")
    void delete_inUseLabel_throwsAndKeepsRow() {
        Label label = persistLabel("PIPE");
        ResourceEntity resource = new ResourceEntity();
        resource.setExternalId("res_in_use");
        resource.setName("Resource In Use");
        resource.setLabels("PIPE");
        resource.setLabelEntities(new ArrayList<>(List.of(label))); // node_labels row
        em.persist(resource);
        em.flush();
        em.clear();

        assertThrows(EntityInUseException.class, () -> labelService.delete(byId(label.getId())));
        assertThat(labelRepository.findById(label.getId())).isPresent();
    }

    // --- resolveLabelUpdate applied to a persisted node: set/add/remove combinations -----------

    @Test
    @DisplayName("null update leaves the node's labels (string + M2M) untouched")
    void update_null_leavesLabelsUntouched() {
        seedLabels("DATASET", "ALPHA");
        DatasetEntity ds = persistDataset("ds_null", "DATASET,ALPHA");

        DatasetEntity r = applyAndReload(ds, null);

        assertThat(labelString(r)).containsExactlyInAnyOrder("DATASET", "ALPHA");
        assertThat(m2mNames(r)).containsExactlyInAnyOrder("DATASET", "ALPHA");
    }

    @Test
    @DisplayName("empty set/add/remove is treated as no change")
    void update_allEmpty_leavesLabelsUntouched() {
        seedLabels("DATASET", "ALPHA");
        DatasetEntity ds = persistDataset("ds_empty", "DATASET,ALPHA");

        var update = new UpdateListField().set(List.of()).add(List.of()).remove(List.of());
        DatasetEntity r = applyAndReload(ds, update);

        assertThat(labelString(r)).containsExactlyInAnyOrder("DATASET", "ALPHA");
        assertThat(m2mNames(r)).containsExactlyInAnyOrder("DATASET", "ALPHA");
    }

    @Test
    @DisplayName("set replaces the non-type labels wholesale")
    void update_setOnly_replaces() {
        seedLabels("DATASET", "ALPHA", "BETA");
        DatasetEntity ds = persistDataset("ds_set", "DATASET,ALPHA");

        DatasetEntity r = applyAndReload(ds, new UpdateListField().set(List.of("DATASET", "BETA")));

        assertThat(labelString(r)).containsExactlyInAnyOrder("DATASET", "BETA");
        assertThat(m2mNames(r)).containsExactlyInAnyOrder("DATASET", "BETA");
    }

    @Test
    @DisplayName("add and remove in the same request apply to the node's current labels")
    void update_addAndRemove_sameRequest() {
        seedLabels("DATASET", "ALPHA", "BETA");
        DatasetEntity ds = persistDataset("ds_add_remove", "DATASET,ALPHA");

        var update = new UpdateListField().add(List.of("BETA")).remove(List.of("ALPHA"));
        DatasetEntity r = applyAndReload(ds, update);

        // current {DATASET,ALPHA} + BETA - ALPHA => {DATASET,BETA}
        assertThat(labelString(r)).containsExactlyInAnyOrder("DATASET", "BETA");
        assertThat(m2mNames(r)).containsExactlyInAnyOrder("DATASET", "BETA");
    }

    @Test
    @DisplayName("set combined with add/remove is rejected — callers must use one or the other")
    void update_setCombinedWithAddOrRemove_isRejected() {
        seedLabels("DATASET", "ALPHA", "BETA");
        DatasetEntity ds = persistDataset("ds_set_plus", "DATASET,ALPHA");

        var setPlusAdd = new UpdateListField().set(List.of("DATASET", "BETA")).add(List.of("ALPHA"));
        assertThrows(InvalidResourceException.class, () -> labelService.resolveLabelUpdate(ds, setPlusAdd));

        var setPlusRemove = new UpdateListField().set(List.of("DATASET", "BETA")).remove(List.of("ALPHA"));
        assertThrows(InvalidResourceException.class, () -> labelService.resolveLabelUpdate(ds, setPlusRemove));
    }

    @Test
    @DisplayName("removing the type-label is ignored — DATASET stays")
    void update_removeTypeLabel_ignored() {
        seedLabels("DATASET", "ALPHA");
        DatasetEntity ds = persistDataset("ds_rm_type", "DATASET,ALPHA");

        DatasetEntity r = applyAndReload(ds, new UpdateListField().remove(List.of("DATASET")));

        assertThat(labelString(r)).containsExactlyInAnyOrder("DATASET", "ALPHA");
        assertThat(m2mNames(r)).containsExactlyInAnyOrder("DATASET", "ALPHA");
    }

    @Test
    @DisplayName("adding a foreign type-label is stripped — a dataset can't become an asset")
    void update_addForeignTypeLabel_stripped() {
        seedLabels("DATASET", "ALPHA");
        DatasetEntity ds = persistDataset("ds_foreign", "DATASET,ALPHA");

        DatasetEntity r = applyAndReload(ds, new UpdateListField().add(List.of("ASSET")));

        assertThat(labelString(r)).containsExactlyInAnyOrder("DATASET", "ALPHA");
        assertThat(m2mNames(r)).containsExactlyInAnyOrder("DATASET", "ALPHA");
    }
}
