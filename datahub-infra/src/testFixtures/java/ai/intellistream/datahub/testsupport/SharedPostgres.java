// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.testsupport;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * One PostgreSQL container, migrated once, shared by every integration test that needs one.
 *
 * <p>Each test class used to start its own container and run the full migration set into it. This
 * starts one container, applies the migrations a single time to a <em>template</em> database, and
 * gives each class its own copy of that template.
 *
 * <h2>Why a template database rather than a schema</h2>
 * The obvious alternatives both give up something this needs. Sharing one migrated database and
 * relying on the {@code @DataJpaTest} rollback does not isolate the classes that opt out of it —
 * {@code EventDimensionRepositoryIT} runs {@code NOT_SUPPORTED} and {@code LabelServiceIT} commits
 * in a nested {@code REQUIRES_NEW} — so their rows would outlive them and leak sideways. Giving each
 * class its own schema would isolate them, but the migrations are written against unqualified names
 * and would have to be re-run per schema, which is the cost this exists to remove.
 *
 * <p>{@code CREATE DATABASE ... TEMPLATE} keeps both properties: total isolation, and a copy that is
 * a filesystem-level clone rather than 30-odd migrations replayed. The copy carries Flyway's history
 * table too, so a class that inspects schema version sees what production would.
 *
 * <h2>The schema is the real one</h2>
 * These migrations, not a Hibernate-generated schema and not a hand-written cut-down script. A
 * schema generated from the entities gives a column's {@code NOT NULL} but not the {@code DEFAULT}
 * the migration declares, no indexes, no partial indexes, no check constraints — and it cannot
 * disagree with the entities even when the migrations do, so schema drift is invisible to it. A
 * cut-down script is a second definition of the same tables, free to drift silently in the direction
 * of the test passing while production breaks.
 *
 * <p>Flyway is driven through its own API rather than {@code spring.flyway.*}: in Boot 4 the
 * auto-configuration lives in a separate {@code spring-boot-flyway} module that this module does not
 * depend on and {@code @DataJpaTest} does not pull in, so those properties would be read by nobody
 * and the schema would silently not exist.
 *
 * <h2>Lifecycle</h2>
 * Started on first use and stopped by a JVM shutdown hook, rather than by {@code @Testcontainers}/
 * {@code @Container}, which stop a container when its declaring class finishes — the opposite of
 * sharing. The hook is required because this build disables Ryuk, the reaper that would otherwise
 * clean up after the JVM, as it does not work under rootless Podman.
 */
public final class SharedPostgres {

    /**
     * Matches the major version production runs (see {@code docker-compose.yml}). The classes this
     * replaces were split between 16 and 18 for no stated reason; testing schema behaviour against
     * the version actually deployed is the only choice with an argument behind it.
     */
    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:18-alpine");

    /** Migrated once; every per-class database is a copy of it. */
    private static final String TEMPLATE = "datahub_migrated";

    private static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>(IMAGE);

    static {
        CONTAINER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(CONTAINER::stop));

        execute("CREATE DATABASE " + TEMPLATE);
        Flyway.configure()
                .dataSource(urlFor(TEMPLATE), username(), password())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private SharedPostgres() {
    }

    /**
     * A fresh database for one test class, cloned from the migrated template.
     *
     * <p>Call once per class from {@code @DynamicPropertySource} and register the returned URL.
     *
     * @param database name unique to the calling class
     * @return the JDBC URL to point {@code spring.datasource.url} at
     */
    public static String newDatabase(String database) {
        // A template cannot be copied while anything is connected to it. Flyway has long since
        // closed its pool, but a stray connection would fail the copy with a message about the
        // source database being in use, so evict anything that is somehow still attached.
        execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity"
                + " WHERE datname = '" + TEMPLATE + "' AND pid <> pg_backend_pid()");
        execute("DROP DATABASE IF EXISTS " + database);
        execute("CREATE DATABASE " + database + " TEMPLATE " + TEMPLATE);
        return urlFor(database);
    }

    public static String username() {
        return CONTAINER.getUsername();
    }

    public static String password() {
        return CONTAINER.getPassword();
    }

    private static String urlFor(String database) {
        return "jdbc:postgresql://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(5432)
                + "/" + database;
    }

    /**
     * Run one statement against the container's own database. CREATE/DROP DATABASE cannot run
     * inside a transaction block, so this uses a plain autocommit connection rather than anything
     * Spring-managed.
     */
    private static void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(
                urlFor(CONTAINER.getDatabaseName()), username(), password());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("Failed on the shared PostgreSQL container: " + sql, e);
        }
    }
}
