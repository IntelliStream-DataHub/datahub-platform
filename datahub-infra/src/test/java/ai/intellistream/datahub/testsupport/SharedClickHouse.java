// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.testsupport;

import com.clickhouse.client.api.Client;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * One ClickHouse container shared by every integration test that needs a plain one.
 *
 * <p>Each test class used to start its own, which is several seconds of image start and health
 * check per class for containers that were byte-identical. This starts one for the JVM and hands
 * each class its own <em>database</em> inside it.
 *
 * <p><b>Per-class databases, not a shared one.</b> The test classes were written against isolated
 * containers and freely reuse table names — two create {@code events}, two create
 * {@code datapoints_float} — with different DDL. Sharing the {@code default} database would make
 * them fight over those names, in an order-dependent way that would look like a flaky test rather
 * than a collision. A database each keeps every class's DDL exactly as it was.
 *
 * <p><b>Lifecycle.</b> Started once on first use and stopped by a JVM shutdown hook, rather than by
 * {@code @Testcontainers}/{@code @Container}, which stop a container when its declaring class
 * finishes — the opposite of sharing. The hook matters here: this build disables Ryuk, the reaper
 * that would otherwise clean up after the JVM, because it does not work under rootless Podman (see
 * {@code configureContainerRuntime} in build.gradle). Without the hook the container would outlive
 * the test run.
 *
 * <p><b>Not for every ClickHouse test.</b> A class needing a differently-configured server still
 * declares its own container — {@code ClickHouseTimezoneFilterIT} mounts a config file to force a
 * non-UTC server timezone, which is the premise of what it asserts and cannot come from a shared
 * default-UTC server.
 */
public final class SharedClickHouse {

    /** Matches the image the sharing test classes each declared before this existed. */
    private static final DockerImageName IMAGE =
            DockerImageName.parse("clickhouse/clickhouse-server:24.8");

    /**
     * Explicit credentials and a /ping wait, both load-bearing and both inherited from the
     * per-class containers this replaces.
     *
     * <p>clickhouse-server:24.8 rejects the default user with the empty password the Testcontainers
     * clickhouse module would otherwise use. {@code withUsername}/{@code withPassword} feed the
     * module's {@code configure()}, which sets the {@code CLICKHOUSE_USER}/{@code CLICKHOUSE_PASSWORD}
     * env the image honours, and {@code getUsername()}/{@code getPassword()} then return the matching
     * credentials. Readiness is waited on via {@code /ping}, which needs no auth, so startup does not
     * depend on the credentials being right.
     */
    private static final ClickHouseContainer CONTAINER =
            new ClickHouseContainer(IMAGE)
                    .withUsername("tester")
                    .withPassword("test")
                    .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    static {
        CONTAINER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(CONTAINER::stop));
    }

    private SharedClickHouse() {
    }

    /**
     * A client bound to its own freshly created database.
     *
     * <p>Call once per test class, passing a name unique to that class, and use the returned client
     * exactly as one built against a private container: the DDL it runs lands in a database nothing
     * else touches.
     *
     * @param database the database to create and connect to; {@code IF NOT EXISTS}, so re-running a
     *                 class within one JVM is harmless
     */

    /**
     * Run a statement and release its response.
     *
     * <p>{@code client.query(sql).get()} hands back a {@code QueryResponse} that holds a pooled
     * connection until it is closed. For a DDL or an INSERT there is nothing to read from it, so it
     * is easy to drop on the floor — and the leak is bounded and invisible while every statement
     * runs in {@code @BeforeAll}. Add a few inserts inside test methods and the pool runs out, at
     * which point some unrelated later test fails with a 40-second connection timeout that reads as
     * container flake rather than as a resource bug. That is what it cost to find the first time.
     */
    public static void execute(Client client, String sql) {
        try (var ignored = client.query(sql).get()) {
            // DDL and INSERT return no rows worth reading.
        } catch (Exception e) {
            throw new IllegalStateException("ClickHouse statement failed: " + sql, e);
        }
    }

    public static Client newClient(String database) {
        return clientBuilder(database).build();
    }

    /**
     * As {@link #newClient}, but returns the builder so a class can add its own settings — the load
     * test raises the socket and execution timeouts, for instance. The database is created before
     * this returns, so the caller only has to call {@code build()}.
     */
    public static Client.Builder clientBuilder(String database) {
        try (Client admin = builderFor("default").build()) {
            admin.query("CREATE DATABASE IF NOT EXISTS " + database).get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create ClickHouse database " + database, e);
        }
        return builderFor(database);
    }

    private static Client.Builder builderFor(String database) {
        return new Client.Builder()
                .addEndpoint("http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(8123))
                .setUsername(CONTAINER.getUsername())
                .setPassword(CONTAINER.getPassword())
                .setDefaultDatabase(database);
    }
}
