// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.testsupport.SharedPostgres;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.NodeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executes the dataset-ACL closure query against a real PostgreSQL, because a recursive CTE cannot
 * be verified by reading it. Covers the traversal direction, cycle termination, diamonds, and the
 * two restrictions that stop the closure widening an ACL: dataset-only nodes, and
 * {@code BELONGS_TO}-only edges.
 *
 * <p>Run with {@code ./gradlew :datahub-infra:integrationTest} on a host with Docker/Podman.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = DatasetClosureIT.JpaConfig.class)
class DatasetClosureIT {

    private static final String BELONGS_TO = "BELONGS_TO";


    /**
     * A migrated database of its own, from {@link SharedPostgres} — this test checks a recursive CTE
     * against the schema production runs, which is the only version of it worth checking.
     *
     * <p>{@code ddl-auto=none} so Hibernate creates nothing behind Flyway's back.
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("dataset_closure_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = DataSetRepository.class)
    @EntityScan(basePackageClasses = DatasetEntity.class)
    static class JpaConfig {
    }

    @Autowired
    private DataSetRepository dataSetRepository;

    @PersistenceContext
    private EntityManager em;

    /** externalId -> node id, for the datasets seeded below. */
    private final Map<String, Long> ids = new HashMap<>();

    /**
     * The migrations create and seed {@code node_type} (ASSET is 1, DATASET is 5 after V12), so
     * nothing here has to. They do not seed {@code relationship_type} — edges name their type on
     * demand at runtime — so the two this test traverses are inserted, and the closure query matches
     * on {@code relationship_type.name} rather than the id.
     */
    @BeforeEach
    void seed() {
        ids.clear();
        em.createNativeQuery("""
                INSERT INTO relationship_type (id, name, hash, date_created, last_updated) VALUES
                  (1, 'BELONGS_TO', 1, now(), now()),
                  (2, 'FLOWS_TO', 2, now(), now())
                ON CONFLICT (id) DO NOTHING""").executeUpdate();
        em.flush();
    }

    private long dataset(String externalId) {
        DatasetEntity ds = new DatasetEntity();
        ds.setExternalId(externalId);
        ds.setName(externalId);
        ds.setLabels("DATASET");
        em.persist(ds);
        em.flush();
        ids.put(externalId, ds.getId());
        return ds.getId();
    }

    /**
     * An asset (node_type 1), used to prove non-dataset nodes never enter the closure.
     *
     * <p>Persisted through {@link AssetEntity} rather than a hand-written INSERT, for the same
     * reason {@link #dataset} is: a literal INSERT has to name every non-nullable column and so
     * breaks the next time one is added to {@code node}. Letting the entity write the row keeps the
     * fixture correct by construction.
     */
    private long asset(String externalId) {
        AssetEntity asset = new AssetEntity();
        asset.setExternalId(externalId);
        asset.setName(externalId);
        asset.setLabels("ASSET");
        em.persist(asset);
        em.flush();
        return asset.getId();
    }

    /** Stored as parent -> child, which is how DataSetTransformer writes connectedDataSets. */
    private void belongsTo(long parentId, long childId) {
        edge(parentId, childId, 1);
    }

    private void edge(long startId, long endId, long relationshipTypeId) {
        em.createNativeQuery("""
                INSERT INTO edge (relationship_type_id, rel_start, rel_end, version, date_created, last_updated)
                VALUES (:rt, :start, :end, 0, now(), now())""")
                .setParameter("rt", relationshipTypeId)
                .setParameter("start", startId)
                .setParameter("end", endId)
                .executeUpdate();
        em.flush();
    }

    private List<Long> closureOf(String... rootExternalIds) {
        List<Long> roots = List.of(rootExternalIds).stream().map(ids::get).toList();
        return dataSetRepository.findDatasetClosure(roots, NodeType.DATASET, BELONGS_TO);
    }

    @Test
    void aLeafGrantCoversOnlyItself() {
        dataset("leaf");

        assertThat(closureOf("leaf")).containsExactly(ids.get("leaf"));
    }

    @Test
    void aRootGrantCoversEveryDescendant() {
        long root = dataset("root");
        long mid = dataset("mid");
        long leaf = dataset("leaf");
        long sibling = dataset("sibling");
        belongsTo(root, mid);
        belongsTo(mid, leaf);
        belongsTo(root, sibling);

        assertThat(closureOf("root")).containsExactlyInAnyOrder(root, mid, leaf, sibling);
    }

    /** The grant travels down, never up: holding the leaf must not expose its parents. */
    @Test
    void aGrantDoesNotTravelUpwards() {
        long root = dataset("root");
        long leaf = dataset("leaf");
        belongsTo(root, leaf);

        assertThat(closureOf("leaf")).containsExactly(leaf);
    }

    /** Nothing prevents connectedDataSets forming a cycle, so the recursion must still terminate. */
    @Test
    void terminatesOnACycle() {
        long a = dataset("ds_a");
        long b = dataset("ds_b");
        long c = dataset("ds_c");
        belongsTo(a, b);
        belongsTo(b, c);
        belongsTo(c, a);

        assertThat(closureOf("ds_a")).containsExactlyInAnyOrder(a, b, c);
    }

    /** Two paths to the same dataset must yield it once, not twice. */
    @Test
    void collapsesADiamond() {
        long root = dataset("root");
        long left = dataset("left");
        long right = dataset("right");
        long shared = dataset("shared");
        belongsTo(root, left);
        belongsTo(root, right);
        belongsTo(left, shared);
        belongsTo(right, shared);

        assertThat(closureOf("root")).containsExactlyInAnyOrder(root, left, right, shared);
    }

    @Test
    void mergesTheClosuresOfSeveralRoots() {
        long a = dataset("ds_a");
        long aChild = dataset("ds_a_child");
        long b = dataset("ds_b");
        long bChild = dataset("ds_b_child");
        dataset("unrelated");
        belongsTo(a, aChild);
        belongsTo(b, bChild);

        assertThat(closureOf("ds_a", "ds_b")).containsExactlyInAnyOrder(a, aChild, b, bChild);
    }

    /**
     * The edge table is shared with ordinary resource relationships, so a non-BELONGS_TO edge must
     * never widen the closure.
     */
    @Test
    void ignoresRelationshipsOtherThanBelongsTo() {
        long root = dataset("root");
        long other = dataset("other");
        edge(root, other, 2);   // FLOWS_TO

        assertThat(closureOf("root")).containsExactly(root);
    }

    /** A BELONGS_TO edge onto a non-dataset node must not drag that node into a dataset ACL. */
    @Test
    void ignoresNonDatasetNodes() {
        long root = dataset("root");
        long assetId = asset("some_asset");
        belongsTo(root, assetId);

        assertThat(closureOf("root")).containsExactly(root);
    }

    @Test
    void resolvesExternalIdsToDatasetIdsOnly() {
        long ds = dataset("wanted");
        asset("decoy");
        List<Long> hashes = List.of(
                ExternalIds.hash("wanted"),
                ExternalIds.hash("decoy"),
                ExternalIds.hash("does_not_exist"));

        assertThat(dataSetRepository.findDatasetIdsByExternalIdHashIn(hashes, NodeType.DATASET))
                .containsExactly(ds);
    }

    /**
     * External ids are matched case-insensitively, because {@link ExternalIds#hash} lowercases
     * before hashing and every write path goes through it.
     *
     * <p>Here because this fixture used to derive the hash itself, with a raw
     * {@code LongHashFunction.xx3().hashChars(externalId)} that skipped the lowercasing — and then
     * queried with the same raw call. Writer and reader agreed with each other and disagreed with
     * production; the test passed only because every id in it happened to be lowercase. This case
     * is the one that would have failed.
     */
    @Test
    void resolvesExternalIdsRegardlessOfCase() {
        long ds = dataset("Mixed_Case_Dataset");

        assertThat(dataSetRepository.findDatasetIdsByExternalIdHashIn(
                List.of(ExternalIds.hash("mixed_case_dataset")), NodeType.DATASET))
                .containsExactly(ds);
    }
}
