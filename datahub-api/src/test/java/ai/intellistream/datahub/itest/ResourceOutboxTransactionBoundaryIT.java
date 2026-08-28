// SPDX-License-Identifier: AGPL-3.0-or-later
// Outside ai.intellistream.datahub.api on purpose: the application component-scans that package,
// so a nested @SpringBootConfiguration there would be picked up by every full-context test.
package ai.intellistream.datahub.itest;

import ai.intellistream.datahub.api.messaging.events.ResourceCudPublishEvent;
import ai.intellistream.datahub.api.messaging.outbox.ResourceOutboxDrainService;
import ai.intellistream.datahub.api.messaging.outbox.ResourceOutboxWriter;
import ai.intellistream.datahub.jpa.domains.AssetEntity;
import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventObject;
import ai.intellistream.datahub.pulsar.ResourceCudMessage;
import ai.intellistream.datahub.repositories.label.LabelRepository;
import ai.intellistream.datahub.repositories.outbox.ResourceOutboxRepository;
import ai.intellistream.datahub.testsupport.SharedPostgres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The guarantee the outbox exists to provide: the queued sync command and the change it describes
 * commit together, or neither does.
 *
 * <p>The service under test is a real container-managed bean, not one built with {@code new}.
 * {@code @Transactional} and {@code @TransactionalEventListener} are both applied by proxies, so a
 * hand-constructed object would ignore the very boundary being asserted.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = ResourceOutboxTransactionBoundaryIT.JpaConfig.class)
// No test-managed transaction: it would swallow the commit whose boundary is under test.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ResourceOutboxTransactionBoundaryIT {

    private static final String TENANT = "acme";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("resource_outbox_boundary_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = {LabelRepository.class, ResourceOutboxRepository.class})
    @EntityScan(basePackageClasses = Label.class)
    @EnableTransactionManagement
    static class JpaConfig {

        /** The real writer, so BEFORE_COMMIT really joins the caller's transaction. */
        @Bean
        ResourceOutboxWriter resourceOutboxWriter(ResourceOutboxRepository repository) {
            // The drain is an after-commit concern and needs Neo4j; this test is about the write.
            return new ResourceOutboxWriter(repository, mock(ResourceOutboxDrainService.class));
        }

        @Bean
        AssetWritingService assetWritingService(ApplicationEventPublisher publisher) {
            return new AssetWritingService(publisher);
        }
    }

    /** Stands in for ResourceService: writes a node and announces it, in one transaction. */
    static class AssetWritingService {

        @PersistenceContext
        private EntityManager em;

        private final ApplicationEventPublisher publisher;

        AssetWritingService(ApplicationEventPublisher publisher) {
            this.publisher = publisher;
        }

        @Transactional
        public Long createAsset(String externalId) {
            AssetEntity asset = newAsset(externalId);
            em.persist(asset);
            em.flush();
            publisher.publishEvent(new ResourceCudPublishEvent(message(asset.getId(), externalId)));
            return asset.getId();
        }

        @Transactional
        public void createAssetThenFail(String externalId) {
            AssetEntity asset = newAsset(externalId);
            em.persist(asset);
            em.flush();
            publisher.publishEvent(new ResourceCudPublishEvent(message(asset.getId(), externalId)));
            throw new IllegalStateException("validation failed after the event was published");
        }

        /** Deliberately missing {@code @Transactional} — the mistake the writer must not absorb. */
        public void createAssetOutsideATransaction(String externalId) {
            publisher.publishEvent(new ResourceCudPublishEvent(message(1L, externalId)));
        }

        private static AssetEntity newAsset(String externalId) {
            AssetEntity asset = new AssetEntity();
            asset.setExternalId(externalId);
            asset.setName("Pump A");
            asset.setLabels("asset");
            return asset;
        }

        private static ResourceCudMessage message(Long id, String externalId) {
            ResourceCudMessage message =
                    new ResourceCudMessage(EventAction.CREATE, EventObject.RESOURCE_AND_RELATION, TENANT);
            Resource resource = new Resource();
            resource.setId(id);
            resource.setExternalId(externalId);
            message.setResources(List.of(resource));
            return message;
        }
    }

    @Autowired
    private AssetWritingService service;

    @Autowired
    private ResourceOutboxRepository outbox;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager em;

    @BeforeEach
    void setUp() {
        // The class runs without a test-managed transaction, so committed rows outlive each test.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            em.createQuery("DELETE FROM AssetEntity").executeUpdate();
            em.createQuery("DELETE FROM ResourceOutboxEntity").executeUpdate();
        });
    }

    private long countAssets() {
        return (Long) em.createQuery("SELECT count(a) FROM AssetEntity a").getSingleResult();
    }

    @Test
    void aCommittedChangeLeavesAQueuedCommandBehind() {
        service.createAsset("asset_committed");

        assertThat(outbox.findAll()).hasSize(1);
        assertThat(outbox.findAll().get(0).getPayload()).contains("upsertNodeIds");
    }

    @Test
    void aRolledBackChangeLeavesNoQueuedCommand() {
        // This is the half the previous after-commit publish already had. Keeping it means the
        // graph is never told about a node that does not exist.
        assertThatThrownBy(() -> service.createAssetThenFail("asset_rolled_back"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(outbox.findAll()).isEmpty();
        assertThat(countAssets()).isZero();
    }

    @Test
    void queuingOutsideATransactionFailsLoudlyInsteadOfDroppingTheChange() {
        // A BEFORE_COMMIT listener is skipped silently when there is no transaction to commit.
        // Silence here would mean a change Postgres keeps and the graph never hears about — the
        // exact failure this table was introduced to remove.
        assertThatThrownBy(() -> service.createAssetOutsideATransaction("asset_no_tx"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a transaction");

        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void aQueuedRowIsImmediatelyDueAndUnapplied() {
        service.createAsset("asset_due");

        var row = outbox.findAll().get(0);
        assertThat(row.getAppliedAt()).isNull();
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getNextAttemptAt()).isNotNull();
        assertThat(row.getCreatedAt()).isNotNull();
    }
}
