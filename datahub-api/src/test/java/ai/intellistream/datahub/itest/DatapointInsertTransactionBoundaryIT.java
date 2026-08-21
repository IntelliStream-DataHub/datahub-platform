// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.itest;

// Deliberately outside ai.intellistream.datahub.api: ApiDatahubApplication component-scans that
// package, so a @SpringBootConfiguration nested in a test there is picked up by every full-context
// test in the module and collides with the app's own @EnableJpaRepositories.

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.services.TimeseriesService;
import ai.intellistream.datahub.api.datasecurity.DatasetAccessDeniedException;
import ai.intellistream.datahub.api.datasecurity.DatasetPermissions;
import ai.intellistream.datahub.api.datasecurity.TestDataSecurity;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.DataWrapperBin;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.api.responses.DatapointsCollection;
import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;
import ai.intellistream.datahub.repositories.node.TimeseriesRepository;
import ai.intellistream.datahub.tenant.TenantContext;
import ai.intellistream.datahub.testsupport.SharedPostgres;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.pulsar.client.api.Producer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transaction boundary around {@link TimeseriesService#insertDatapoints}, against a real
 * PostgreSQL and a real persistence context.
 *
 * <p>The unit test beside this one mocks the repository and {@link DataSecurity}, so it cannot see
 * either of the two things that actually depend on JPA:
 *
 * <ol>
 *   <li><strong>The permission check still works.</strong> {@code NodeEntity.dataSet} is a lazy
 *       association that {@link DataSecurity#assertCanWrite} dereferences, and
 *       {@code spring.jpa.open-in-view} is false. Had the transaction simply been dropped from the
 *       method rather than scoped to the read phase, every insert would fail on a detached lazy
 *       proxy, and no mocked test would notice.</li>
 *   <li><strong>The publish happens outside that transaction.</strong> That is the whole point of
 *       the change: holding a pooled connection across the Pulsar round trip is what forced
 *       {@code blockIfQueueFull} off. Only a real transaction manager can be asked whether a
 *       transaction is active at the moment of the send.</li>
 * </ol>
 *
 * <p>Run with {@code ./gradlew :datahub-api:integrationTest} on a host with Podman or Docker.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = DatapointInsertTransactionBoundaryIT.JpaConfig.class)
// No test-managed transaction. @DataJpaTest wraps each test in one by default, which would both
// hide the boundary under test (the template would join the test's transaction, so a transaction
// would always look active during the send) and make the assertions meaningless.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DatapointInsertTransactionBoundaryIT {

    private static final String TENANT = "acme";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = SharedPostgres.newDatabase("datapoint_insert_boundary_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    /**
     * Per-test state reachable from the context's singleton beans. Static because the beans are
     * built once for the class while the values change per test.
     */
    private static final AtomicReference<DatasetPermissions> PERMISSIONS =
            new AtomicReference<>(DatasetPermissions.none());
    private static final AtomicBoolean TX_ACTIVE_DURING_SEND = new AtomicBoolean();
    private static final AtomicInteger SENDS = new AtomicInteger();

    /**
     * {@link TimeseriesService} is registered as a real bean, not built with {@code new}, and that
     * detail is the point of this class. {@code @Transactional} is applied by a Spring proxy, so a
     * hand-constructed instance ignores it completely: an earlier version of this test built the
     * service by reflection and still passed with {@code @Transactional} put back on the method,
     * which made it worthless as a guard. Going through the container means the annotation is
     * honoured, and re-adding it fails
     * {@link #publishHappensOutsideTheTransaction()} as it should.
     */
    @SpringBootConfiguration
    @EnableJpaRepositories(basePackageClasses = TimeseriesRepository.class)
    @EntityScan(basePackageClasses = TimeseriesEntity.class)
    @EnableTransactionManagement
    static class JpaConfig {

        @Bean
        TransactionTemplate datapointTransactionTemplate(PlatformTransactionManager tm) {
            return new TransactionTemplate(tm);
        }

        @Bean
        DataSecurity dataSecurity() {
            return TestDataSecurity.backedBy(PERMISSIONS::get);
        }

        @Bean
        @SuppressWarnings("unchecked")
        Producer<DataWrapperBin> allDatapointProducer() throws Exception {
            Producer<DataWrapperBin> mock = Mockito.mock(Producer.class);
            Mockito.when(mock.send(Mockito.any(DataWrapperBin.class))).thenAnswer(inv -> {
                TX_ACTIVE_DURING_SEND.set(TransactionSynchronizationManager.isActualTransactionActive());
                SENDS.incrementAndGet();
                return null;
            });
            return mock;
        }

        /**
         * Real collaborators for the parts under test, mocks for the rest, matched by parameter
         * type rather than position: the constructor is Lombok-generated from the field list, so a
         * positional call would silently bind the wrong arguments the next time a field is added.
         */
        @Bean
        TimeseriesService timeseriesService(TimeseriesRepository repository,
                                            DataSecurity dataSecurity,
                                            TransactionTemplate datapointTransactionTemplate,
                                            Producer<DataWrapperBin> allDatapointProducer) throws Exception {
            Map<Class<?>, Object> real = Map.of(
                    TimeseriesRepository.class, repository,
                    DataSecurity.class, dataSecurity,
                    TransactionTemplate.class, datapointTransactionTemplate,
                    Producer.class, allDatapointProducer);
            Constructor<?> ctor = TimeseriesService.class.getDeclaredConstructors()[0];
            Class<?>[] types = ctor.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < args.length; i++) {
                args[i] = real.containsKey(types[i]) ? real.get(types[i]) : Mockito.mock(types[i]);
            }
            ctor.setAccessible(true);
            return (TimeseriesService) ctor.newInstance(args);
        }
    }

    @Autowired
    private TimeseriesRepository timeseriesRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private TimeseriesService timeseriesService;

    private TransactionTemplate tx;

    private long datasetId;
    private String timeseriesExternalId;

    /**
     * Each test seeds its own dataset and timeseries. The seed commits, because this class runs
     * without a test-managed transaction, so reusing one external id across tests would hit the
     * unique index on node.external_id_hash on the second test.
     */
    private static final AtomicInteger SEED = new AtomicInteger();

    @BeforeEach
    void seed() throws Exception {
        TenantContext.setTenantId(TENANT);
        tx = new TransactionTemplate(transactionManager);
        TX_ACTIVE_DURING_SEND.set(false);
        SENDS.set(0);

        // Committed, because this test runs without a test-managed transaction. SharedPostgres
        // hands this class its own database, so nothing leaks sideways.
        int n = SEED.incrementAndGet();
        String datasetExternalId = "rack-" + n;
        timeseriesExternalId = "pump-" + n + "-power";

        datasetId = tx.execute(status -> {
            DatasetEntity ds = new DatasetEntity();
            ds.setExternalId(datasetExternalId);
            ds.setName(datasetExternalId);
            ds.setLabels("DATASET");
            em.persist(ds);

            TimeseriesEntity ts = new TimeseriesEntity();
            ts.setExternalId(timeseriesExternalId);
            ts.setName(timeseriesExternalId);
            ts.setLabels("TIMESERIES");
            ts.setValueType(new TimeseriesValueType(TimeseriesValueType.FLOAT));
            ts.setDataSet(ds);
            em.persist(ts);
            em.flush();
            return ds.getId();
        });

    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static DataWrapper<DatapointsCollection> oneDatapointFor(String externalId) {
        DatapointString dp = new DatapointString();
        dp.setTimestamp("2026-08-21T10:00:00Z");
        dp.setValue("1.5");

        DatapointsCollection c = new DatapointsCollection();
        c.setExternalId(externalId);
        c.setDatapoints(List.of(dp));

        DataWrapper<DatapointsCollection> w = new DataWrapper<>();
        w.getItems().add(c);
        return w;
    }

    // ---- tests ------------------------------------------------------------

    private void callerMayWriteTo(long... datasetIds) {
        Set<Long> ids = new java.util.HashSet<>();
        for (long id : datasetIds) {
            ids.add(id);
        }
        PERMISSIONS.set(DatasetPermissions.of(false, false, Set.of(), ids));
    }

    @Test
    @DisplayName("The lazy dataset association resolves, so the write permission check really runs")
    void permissionCheckResolvesTheLazyDatasetAssociation() throws Exception {
        callerMayWriteTo(datasetId);

        timeseriesService.insertDatapoints(oneDatapointFor(timeseriesExternalId));

        assertThat(SENDS.get()).as("the datapoint should have been published").isEqualTo(1);
    }

    @Test
    @DisplayName("A caller without write access to that dataset is refused")
    void callerWithoutWriteAccessIsRefused() {
        // The complement of the test above, and the reason it means anything: this proves the check
        // read the real dataset id off the lazy association rather than passing vacuously.
        PERMISSIONS.set(DatasetPermissions.of(false, false, Set.of(datasetId), Set.of()));

        assertThatThrownBy(() -> timeseriesService.insertDatapoints(oneDatapointFor(timeseriesExternalId)))
                .isInstanceOf(DatasetAccessDeniedException.class);

        assertThat(SENDS.get()).as("nothing should be published when the caller is refused").isZero();
    }

    @Test
    @DisplayName("No transaction is open while the datapoints are published")
    void publishHappensOutsideTheTransaction() throws Exception {
        callerMayWriteTo(datasetId);

        timeseriesService.insertDatapoints(oneDatapointFor(timeseriesExternalId));

        assertThat(SENDS.get()).isEqualTo(1);
        assertThat(TX_ACTIVE_DURING_SEND.get())
                .as("the send must not hold a database connection: that is what made "
                        + "blockIfQueueFull unsafe to enable")
                .isFalse();
    }
}
