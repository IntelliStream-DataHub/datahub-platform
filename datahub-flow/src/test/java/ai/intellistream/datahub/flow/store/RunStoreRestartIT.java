// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.store;

import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.store.RunStore.Claimed;
import ai.intellistream.datahub.flow.store.RunStore.FlowLimits;
import ai.intellistream.datahub.flow.store.RunStore.NewRun;
import ai.intellistream.datahub.flow.store.RunStore.Outcome;
import ai.intellistream.datahub.flow.store.RunStore.StoredRun;
import ai.intellistream.datahub.tenant.KVRocksTenant;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The run store across a KVRocks that goes down and comes back (RUN_STORE.md: "It writes to disk, so a
 * restart loses nothing"): once stopped in an orderly way and once killed without warning. It has a
 * server of its own, on a port that stays the same across the restart, since the shared ones are
 * other tests'. Queued, running, delayed and finished runs are all as they were, leases keep the
 * server's clock through the outage, and the store works again by itself.
 */
@Tag("integration")
class RunStoreRestartIT {

    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final Duration GRACE = Duration.ofSeconds(60);
    private static final Outcome OUTCOME = new Outcome(null, null, null, null, "{}");
    private static final Instant BASE = Instant.parse("2026-10-12T00:00:00Z");

    private static GenericContainer<?> server;
    private static int port;

    private TenantKvrocks kvrocks;
    private RunStore store;

    @BeforeAll
    static void startServer() throws IOException {
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        server = new GenericContainer<>(DockerImageName.parse("docker.io/apache/kvrocks:2.14.0"))
                .withExposedPorts(6666)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(6666))))
                .waitingFor(Wait.forListeningPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        KVRocksTenant config = new KVRocksTenant();
        config.setHost(server.getHost());
        config.setPort(port);
        Tenant tenant = new Tenant();
        tenant.setOrganizationId("t");
        tenant.setKvrocksTenant(config);
        TenantConfigService tenants = mock(TenantConfigService.class);
        when(tenants.getConfig(anyString())).thenReturn(tenant);
        RedisClient admin = RedisClient.create();
        admin.connect(RedisURI.create(server.getHost(), port)).sync().flushall();
        admin.shutdown();
        kvrocks = new TenantKvrocks(tenants);
        store = new RunStore(kvrocks, Duration.ofMinutes(15));
        TenantContext.setTenantId("t");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        kvrocks.close();
    }

    @Test
    void aServerStoppedInAnOrderlyWayComesBackWithEveryRunItHad() {
        theRunsOfAllKindsAreAsTheyWereAfter(false);
    }

    @Test
    void aServerKilledWithoutWarningComesBackWithEveryRunItHad() {
        theRunsOfAllKindsAreAsTheyWereAfter(true);
    }

    private void theRunsOfAllKindsAreAsTheyWereAfter(boolean kill) {
        store.syncFlow(new FlowLimits(1, "flow-1", 7, true, true, 2));
        NewRun[] r = new NewRun[5];
        for (int i = 0; i < 5; i++) {
            r[i] = run(i);
        }
        store.enqueueAll(List.of(r));
        assertThat(store.claim("a", LEASE, 2, GRACE)).extracting(Claimed::id).containsExactly(r[0].id(), r[1].id());
        assertThat(store.complete(r[0].id(), "a", 1, "SUCCEEDED", false, OUTCOME)).isTrue();
        assertThat(store.claim("a", LEASE, 1, GRACE)).extracting(Claimed::id).containsExactly(r[2].id());
        assertThat(store.retry(r[1].id(), "a", 1, Duration.ofHours(1), OUTCOME)).isTrue();
        // Now: r0 finished, r1 waiting out a back-off on its second attempt, r2 running, r3 and r4 queued.
        RunStore.FlowState before = store.states(List.of(1L)).get(1L);
        StoredRun r1 = store.find(r[1].id()).orElseThrow();

        restart(kill);
        awaitStore();

        assertThat(store.find(r[0].id()).orElseThrow().status()).isEqualTo("SUCCEEDED");
        StoredRun delayed = store.find(r[1].id()).orElseThrow();
        assertThat(delayed.status()).isEqualTo("PENDING");
        assertThat(delayed.attempt()).isEqualTo(2);
        assertThat(delayed.notBefore()).isEqualTo(r1.notBefore());
        StoredRun running = store.find(r[2].id()).orElseThrow();
        assertThat(running.status()).isEqualTo("RUNNING");
        assertThat(running.owner()).isEqualTo("a");
        assertThat(store.states(List.of(1L)).get(1L)).as("what the overview reads").isEqualTo(before);
        assertThat(store.takeUnarchived(10, Duration.ofSeconds(30))).extracting(StoredRun::id)
                .as("the finished run still waits to be archived").containsExactly(r[0].id());

        // The owner's lease is still its own, and the work goes on where it was: the limit of two holds
        // with one running, the earliest queued window starts, the delayed one waits its time.
        assertThat(store.renew(List.of(r[2].id()), "a", LEASE)).hasSize(1);
        assertThat(store.claim("b", LEASE, 5, GRACE)).extracting(Claimed::id).containsExactly(r[3].id());
        assertThat(store.claim("b", LEASE, 5, GRACE)).as("at the limit").isEmpty();
        assertThat(store.complete(r[2].id(), "a", 1, "SUCCEEDED", false, OUTCOME)).isTrue();
        assertThat(store.claim("b", LEASE, 5, GRACE)).extracting(Claimed::id).as("the back-off of r1 is not over")
                .containsExactly(r[4].id());
    }

    @Test
    void aLeaseThatRanOutWhileTheServerWasDownIsReapedAfterIt() throws Exception {
        store.syncFlow(new FlowLimits(1, "flow-1", 7, true, true, 1));
        NewRun only = run(0);
        store.enqueue(only);
        assertThat(store.claim("a", Duration.ofSeconds(2), 1, GRACE)).hasSize(1);

        restart(true);
        awaitStore();
        Thread.sleep(2500);

        assertThat(store.reap(10, Duration.ZERO, Duration.ZERO)).singleElement().satisfies(reaped -> {
            assertThat(reaped.id()).isEqualTo(only.id());
            assertThat(reaped.requeued()).isTrue();
        });
        assertThat(store.renew(List.of(only.id()), "a", LEASE)).as("a no longer holds it").isEmpty();
        assertThat(store.claim("b", LEASE, 1, GRACE)).extracting(Claimed::id).containsExactly(only.id());
    }

    @Test
    void theStoreWorksAgainByItselfAfterAnOutageWithoutBeingRestarted() {
        store.syncFlow(new FlowLimits(1, "flow-1", 7, true, true, 1));
        restart(true);

        NewRun made = run(0);
        awaitStore();

        assertThat(store.enqueue(made)).isTrue();
        assertThat(store.claim("a", LEASE, 1, GRACE)).extracting(Claimed::id).containsExactly(made.id());
    }

    // ---------------------------------------------------------------- fixtures

    private static void restart(boolean kill) {
        var docker = server.getDockerClient();
        String id = server.getContainerId();
        if (kill) {
            docker.killContainerCmd(id).exec();
            docker.startContainerCmd(id).exec();
        } else {
            docker.restartContainerCmd(id).withTimeout(10).exec();
        }
    }

    /** Until the store answers again: its connections were cut, and open themselves again. */
    private void awaitStore() {
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
            try {
                store.now();
                // And a transaction, which uses connections of its own.
                store.cancelPending(UUID.randomUUID(), "X", "x");
                return;
            } catch (RuntimeException e) {
                if (System.nanoTime() > until) {
                    throw new AssertionError("The store did not answer again within 60 s", e);
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }
    }

    private static NewRun run(int hour) {
        Instant start = BASE.plus(Duration.ofHours(hour));
        return new NewRun(UUID.randomUUID(), 1, "flow-1", 7, 1, 3, Duration.ofMinutes(15),
                new TimeWindow(start, start.plus(Duration.ofHours(1))), null, "manual", "user", "user", null);
    }
}
