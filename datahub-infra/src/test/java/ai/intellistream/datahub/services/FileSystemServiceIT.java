// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.services;

import ai.intellistream.datahub.config.FilesConfig;
import ai.intellistream.datahub.helpers.text.ExternalIds;
import ai.intellistream.datahub.jpa.domains.INode;
import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.repositories.files.INodeRepository;
import ai.intellistream.datahub.testsupport.SharedPostgres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Soft delete and restore against a real inode table and a real disk: the row keeps its external id,
 * the file sits in the trash under its id, the external id is free for a new file meanwhile, and a
 * rolled-back transaction puts the disk back where the rows are.
 *
 * <p>Not transactional per test, unlike other {@code @DataJpaTest}s: each step commits or rolls back
 * on its own, since what is under test is exactly what happens at those boundaries.
 */
@Tag("integration")
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = FileSystemServiceIT.JpaConfig.class)
class FileSystemServiceIT {

    @TempDir
    static Path base;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("file_system_service_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = INodeRepository.class)
    @EntityScan(basePackageClasses = NodeEntity.class)
    static class JpaConfig {

        @Bean
        FilesConfig filesConfig() {
            FilesConfig config = mock(FilesConfig.class);
            when(config.getRoot()).thenReturn(base.resolve("root"));
            when(config.getTrash()).thenReturn(base.resolve("trash"));
            return config;
        }

        @Bean
        FileSystemService fileSystemService(FilesConfig filesConfig, INodeRepository iNodeRepository) {
            return new FileSystemService(filesConfig, iNodeRepository);
        }
    }

    @Autowired private FileSystemService fileSystemService;
    @Autowired private INodeRepository iNodeRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    private TransactionTemplate tx;
    private Path root;
    private Path trash;

    @BeforeEach
    void setUp() throws Exception {
        tx = new TransactionTemplate(transactionManager);
        iNodeRepository.deleteAll();
        root = Files.createDirectories(base.resolve("root"));
        trash = Files.createDirectories(base.resolve("trash"));
    }

    private INode file(String externalId, String path) throws Exception {
        Files.writeString(root.resolve(path.substring(1)), externalId);
        INode node = new INode();
        node.setNodeType(INode.INodeType.FILE);
        node.setName(path.substring(path.lastIndexOf('/') + 1));
        node.setExternalId(externalId);
        node.setPath(path);
        node.setChecksum(new byte[]{1, 2, 3});
        return iNodeRepository.save(node);
    }

    private void delete(long id) {
        tx.executeWithoutResult(s -> {
            try {
                fileSystemService.delete(List.of(iNodeRepository.findById(id).orElseThrow()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void restore(long id) {
        tx.executeWithoutResult(s -> {
            try {
                fileSystemService.restore(iNodeRepository.findAllDeletedByIdIn(List.of(id)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void deleteKeepsTheExternalIdAndTrashesTheFileUnderItsId() throws Exception {
        INode report = file("COM-99-Report.pdf", "/report.pdf");

        delete(report.getId());

        INode trashed = iNodeRepository.findById(report.getId()).orElseThrow();
        assertThat(trashed.getExternalId()).isEqualTo("COM-99-Report.pdf");
        assertThat(trashed.getDeletedAt()).isNotNull();
        assertThat(trashed.getTrashName()).isEqualTo(String.valueOf(report.getId()));
        assertThat(root.resolve("report.pdf")).doesNotExist();
        assertThat(trash.resolve(trashed.getTrashName())).hasContent("COM-99-Report.pdf");

        // The cleanup module's TrashPurger reads deleted_at through this same native query and
        // converts the value by type; it handles Instant, OffsetDateTime and Timestamp.
        Object deletedAt = em.createNativeQuery("SELECT deleted_at FROM inodes WHERE id = :id")
                .setParameter("id", report.getId()).getSingleResult();
        assertThat(deletedAt).isInstanceOfAny(Instant.class, OffsetDateTime.class, Timestamp.class);
    }

    @Test
    void theExternalIdIsFreeWhileTrashedAndRestoreRefusesWhileItIsTaken() throws Exception {
        INode original = file("report.pdf", "/report.pdf");
        delete(original.getId());

        // Lookup is case-insensitive, and the trashed row does not hold the id.
        INode replacement = file("REPORT.pdf", "/replacement.pdf");

        assertThatThrownBy(() -> restore(original.getId()))
                .rootCause()
                .isInstanceOf(FileSystemService.RestoreRefusedException.class)
                .hasMessageContaining("external id");
        assertThat(trash.resolve(String.valueOf(original.getId()))).exists();

        delete(replacement.getId());
        restore(original.getId());

        INode back = iNodeRepository.findById(original.getId()).orElseThrow();
        assertThat(back.getDeletedAt()).isNull();
        assertThat(back.getTrashName()).isNull();
        assertThat(root.resolve("report.pdf")).hasContent("report.pdf");
    }

    @Test
    void deleteTargetsResolveByIdOrCaseInsensitiveExternalIdWithEitherSetEmpty() throws Exception {
        INode report = file("COM-99-Report.pdf", "/report.pdf");

        assertThat(iNodeRepository.findAllByIdOrExternalIdHashAndNotDeleted(Set.of(report.getId()), Set.of()))
                .extracting(INode::getId).containsExactly(report.getId());
        assertThat(iNodeRepository.findAllByIdOrExternalIdHashAndNotDeleted(
                Set.of(), Set.of(ExternalIds.hash("com-99-report.PDF"))))
                .extracting(INode::getId).containsExactly(report.getId());
    }

    @Test
    void restoreByExternalIdFindsTheMostRecentlyDeletedCopy() throws Exception {
        INode first = file("report.pdf", "/report.pdf");
        delete(first.getId());
        INode second = file("Report.pdf", "/report.pdf");
        delete(second.getId());

        assertThat(iNodeRepository.findFirstByExternalIdHashAndDeletedAtIsNotNullOrderByDeletedAtDesc(
                ExternalIds.hash("REPORT.PDF")))
                .get().extracting(INode::getId).isEqualTo(second.getId());
    }

    @Test
    void aRolledBackDeletePutsTheDiskBack() throws Exception {
        INode report = file("report.pdf", "/report.pdf");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            try {
                fileSystemService.delete(List.of(iNodeRepository.findById(report.getId()).orElseThrow()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            throw new IllegalStateException("commit fails");
        })).hasMessage("commit fails");

        assertThat(iNodeRepository.findById(report.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(root.resolve("report.pdf")).hasContent("report.pdf");
        assertThat(trash.resolve(String.valueOf(report.getId()))).doesNotExist();
    }
}
