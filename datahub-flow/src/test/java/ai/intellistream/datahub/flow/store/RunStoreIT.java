// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.store;

import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.store.RunStore.Claim;
import ai.intellistream.datahub.flow.store.RunStore.Claimed;
import ai.intellistream.datahub.flow.store.RunStore.FlowLimits;
import ai.intellistream.datahub.flow.store.RunStore.NewPreview;
import ai.intellistream.datahub.flow.store.RunStore.NewRun;
import ai.intellistream.datahub.flow.store.RunStore.Outcome;
import ai.intellistream.datahub.flow.store.RunStore.Reaped;
import ai.intellistream.datahub.flow.store.RunStore.StoredRun;
import ai.intellistream.datahub.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run store against a real KVRocks: each transition of a run, and that the invariants that used
 * to be Postgres constraints hold after random sequences of operations and under concurrent load.
 */
@Tag("integration")
class RunStoreIT {

    private static final Instant BASE = Instant.parse("2026-10-12T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration GRACE = Duration.ofSeconds(60);
    private static final Outcome OUTCOME = new Outcome(null, null, null, null, "{}");

    private TenantKvrocks kvrocks;
    private RunStore store;

    @BeforeEach
    void setUp() {
        SharedKvrocks.flush();
        kvrocks = new TenantKvrocks(SharedKvrocks.tenants("t"));
        store = new RunStore(kvrocks, Duration.ofSeconds(1));
        TenantContext.setTenantId("t");
    }

    @AfterEach
    void tearDown() {
        assertThat(StoreInvariants.violations()).as("the store's invariants").isEmpty();
        TenantContext.clear();
        kvrocks.close();
    }

    // ---------------------------------------------------------------- making and claiming

    @Test
    void enqueueingTheSameRunTwiceMakesItOnce() {
        flow(1, 1);
        NewRun run = run(1, 0);

        assertThat(store.enqueue(run)).isTrue();
        assertThat(store.enqueue(run)).isFalse();

        assertThat(store.claim("a", LEASE, 10, GRACE)).extracting(Claimed::id).containsExactly(run.id());
        assertThat(store.enqueueAll(List.of(run, run(1, 1)))).as("only the new one").isEqualTo(1);
    }

    @Test
    void claimTakesTheEarliestWindowsOfTheFlowsThatHaveRoom() {
        flow(1, 1);
        flow(2, 2);
        NewRun f1h0 = run(1, 0);
        NewRun f1h1 = run(1, 1);
        NewRun f2h2 = run(2, 2);
        NewRun f2h3 = run(2, 3);
        NewRun f2h4 = run(2, 4);
        store.enqueueAll(List.of(f2h4, f1h1, f2h3, f1h0, f2h2));

        List<Claimed> claimed = store.claim("a", LEASE, 10, GRACE);

        // Flow 1 has room for one, flow 2 for two; in the order of their windows.
        assertThat(claimed).extracting(Claimed::id).containsExactly(f1h0.id(), f2h2.id(), f2h3.id());
        assertThat(store.claim("a", LEASE, 10, GRACE)).as("both flows are at their limit").isEmpty();
    }

    @Test
    void aBacklogOfOneFlowDoesNotHideTheRunsOfAnother() {
        flow(1, 1);
        flow(2, 1);
        for (int h = 0; h < 50; h++) {
            store.enqueue(run(1, h));
        }
        NewRun other = run(2, 500);
        store.enqueue(other);

        assertThat(store.claim("a", LEASE, 4, GRACE)).extracting(Claimed::id)
                .contains(other.id()).hasSize(2);
    }

    @Test
    void whenARunEndsTheNextOfItsFlowCanStart() {
        flow(1, 1);
        NewRun first = run(1, 0);
        NewRun second = run(1, 1);
        store.enqueueAll(List.of(first, second));

        Claimed one = store.claim("a", LEASE, 5, GRACE).getFirst();
        assertThat(one.id()).isEqualTo(first.id());
        assertThat(store.claim("a", LEASE, 5, GRACE)).isEmpty();
        assertThat(store.complete(first.id(), "a", 1, "SUCCEEDED", false, OUTCOME)).isTrue();

        assertThat(store.claim("a", LEASE, 5, GRACE)).extracting(Claimed::id).containsExactly(second.id());
    }

    @Test
    void theRunsOfAFlowSwitchedOffAreCancelledInsteadOfStarted() {
        store.syncFlow(new FlowLimits(1, "flow-1", 7, false, true, 1));
        NewRun run = run(1, 0);
        store.enqueue(run);

        assertThat(store.claim("a", LEASE, 5, GRACE)).isEmpty();

        StoredRun cancelled = store.find(run.id()).orElseThrow();
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.errorCode()).isEqualTo("FLOW_DISABLED");
        assertThat(store.unarchived(10)).extracting(StoredRun::id).containsExactly(run.id());
    }

    @Test
    void aFlowNobodyHasDescribedYetRunsOneAtATime() {
        store.enqueueAll(List.of(run(9, 0), run(9, 1)));

        assertThat(store.claim("a", LEASE, 5, GRACE)).hasSize(1);
    }

    @Test
    void claimOneHonoursTheLimitForARunButNotForAPreview() {
        flow(1, 1);
        NewRun first = run(1, 0);
        NewRun second = run(1, 1);
        store.enqueueAll(List.of(first, second));
        UUID preview = UUID.randomUUID();
        store.enqueuePreview(new NewPreview(preview, 1L, 1, "flow-1", null, 7L, Duration.ofMinutes(5),
                new TimeWindow(BASE, BASE.plusSeconds(3600)), null, "user"));

        assertThat(store.claimOne(first.id(), "a", LEASE, GRACE)).isEqualTo(Claim.CLAIMED);
        assertThat(store.claimOne(second.id(), "a", LEASE, GRACE)).as("the flow is at its limit").isEqualTo(Claim.NOT_CLAIMED);
        assertThat(store.claimOne(preview, "a", LEASE, GRACE)).as("a preview is nobody's run").isEqualTo(Claim.CLAIMED);
        assertThat(store.claimOne(preview, "b", LEASE, GRACE)).as("and only one takes it").isEqualTo(Claim.NOT_CLAIMED);
    }

    @Test
    void claimOneCancelsARunWhoseFlowIsSwitchedOff() {
        store.syncFlow(new FlowLimits(1, "flow-1", 7, false, true, 1));
        NewRun run = run(1, 0);
        store.enqueue(run);

        assertThat(store.claimOne(run.id(), "a", LEASE, GRACE)).isEqualTo(Claim.CANCELLED);
        assertThat(store.find(run.id()).orElseThrow().errorCode()).isEqualTo("FLOW_DISABLED");
    }

    // ---------------------------------------------------------------- owning and ending

    @Test
    void onlyTheOwnerRenewsALeaseAndTheAnswerCarriesACancelRequest() {
        flow(1, 2);
        NewRun a = run(1, 0);
        NewRun b = run(1, 1);
        store.enqueueAll(List.of(a, b));
        store.claim("a", LEASE, 5, GRACE);
        assertThat(store.requestCancel(a.id())).isTrue();

        assertThat(store.renew(List.of(a.id(), b.id()), "a", LEASE)).satisfiesExactlyInAnyOrder(
                r -> {
                    assertThat(r.id()).isEqualTo(a.id());
                    assertThat(r.cancelRequested()).isTrue();
                },
                r -> {
                    assertThat(r.id()).isEqualTo(b.id());
                    assertThat(r.cancelRequested()).isFalse();
                });
        assertThat(store.renew(List.of(a.id(), b.id()), "someone-else", LEASE)).isEmpty();
    }

    @Test
    void onlyTheOwnerOfTheCurrentAttemptMayEndIt() {
        flow(1, 1);
        NewRun run = run(1, 0);
        store.enqueue(run);
        store.claim("a", LEASE, 1, GRACE);

        assertThat(store.complete(run.id(), "b", 1, "SUCCEEDED", false, OUTCOME)).isFalse();
        assertThat(store.complete(run.id(), "a", 2, "SUCCEEDED", false, OUTCOME)).isFalse();
        assertThat(store.retry(run.id(), "b", 1, Duration.ZERO, OUTCOME)).isFalse();
        assertThat(store.find(run.id()).orElseThrow().status()).isEqualTo("RUNNING");

        assertThat(store.complete(run.id(), "a", 1, "SUCCEEDED", false, OUTCOME)).isTrue();
        assertThat(store.complete(run.id(), "a", 1, "SUCCEEDED", false, OUTCOME)).as("once").isFalse();
    }

    @Test
    void anEndedRunKeepsItsOutcomeAndLeavesTheFlowsLatestRun() {
        flow(1, 1);
        NewRun run = run(1, 0);
        store.enqueue(run);
        store.claim("a", LEASE, 1, GRACE);

        store.complete(run.id(), "a", 1, "FAILED", true, new Outcome("PROCESSOR_FAILED", "boom", "{\"x\":1}",
                "{\"clip\":\"1.0.0\"}", "{\"records\":3}"));

        StoredRun ended = store.find(run.id()).orElseThrow();
        assertThat(ended.status()).isEqualTo("FAILED");
        assertThat(ended.errorCode()).isEqualTo("PROCESSOR_FAILED");
        assertThat(ended.error()).isEqualTo("boom");
        assertThat(ended.parameters()).isEqualTo("{\"x\":1}");
        assertThat(ended.processorVersions()).isEqualTo("{\"clip\":\"1.0.0\"}");
        assertThat(ended.stats()).isEqualTo("{\"records\":3}");
        assertThat(ended.finishedAt()).isNotNull();
        assertThat(ended.owner()).isNull();
        assertThat(ended.failureEventOwed()).isTrue();
        assertThat(store.owedEvents(10)).extracting(StoredRun::id).containsExactly(run.id());
        assertThat(store.states(List.of(1L)).get(1L).last()).satisfies(last -> {
            assertThat(last.id()).isEqualTo(run.id());
            assertThat(last.status()).isEqualTo("FAILED");
        });
    }

    @Test
    void aRetryWaitsOutItsBackOffAndThenRunsAgainAsTheNextAttempt() throws Exception {
        flow(1, 1);
        NewRun run = run(1, 0);
        store.enqueue(run);
        store.claim("a", LEASE, 1, GRACE);

        assertThat(store.retry(run.id(), "a", 1, Duration.ofMillis(400),
                new Outcome("PROCESSOR_FAILED", "boom", null, null, "{}"))).isTrue();

        StoredRun waiting = store.find(run.id()).orElseThrow();
        assertThat(waiting.status()).isEqualTo("PENDING");
        assertThat(waiting.attempt()).isEqualTo(2);
        assertThat(waiting.errorCode()).isEqualTo("PROCESSOR_FAILED");
        assertThat(store.states(List.of(1L)).get(1L)).satisfies(state -> {
            assertThat(state.queued()).isEqualTo(1);
            assertThat(state.retrying()).isEqualTo(1);
        });
        assertThat(store.claim("a", LEASE, 1, GRACE)).as("not before its time").isEmpty();

        Thread.sleep(500);

        assertThat(store.claim("a", LEASE, 1, GRACE)).singleElement().satisfies(c -> assertThat(c.attempt()).isEqualTo(2));
        assertThat(store.states(List.of(1L)).get(1L).retrying()).isZero();
    }

    @Test
    void aReleasedRunGoesBackAsItWasWithoutCountingTheAttempt() {
        flow(1, 1);
        NewRun run = run(1, 0);
        store.enqueue(run);
        store.claim("a", LEASE, 1, GRACE);

        assertThat(store.release(run.id(), "a", 1)).isTrue();

        StoredRun back = store.find(run.id()).orElseThrow();
        assertThat(back.status()).isEqualTo("PENDING");
        assertThat(back.attempt()).isEqualTo(1);
        assertThat(back.owner()).isNull();
        assertThat(store.claim("b", LEASE, 1, GRACE)).extracting(Claimed::id).containsExactly(run.id());
    }

    // ---------------------------------------------------------------- losing a lease

    @Test
    void theReaperRequeuesARunWhoseLeaseRanOutAndFailsItOnItsLastAttempt() throws Exception {
        flow(1, 2);
        NewRun again = run(1, 0);
        NewRun last = run(UUID.randomUUID(), 1, 1, 1);
        store.enqueueAll(List.of(again, last));
        store.claim("a", Duration.ofMillis(200), 5, GRACE);
        Thread.sleep(300);

        List<Reaped> reaped = store.reap(10, Duration.ZERO, Duration.ZERO);

        assertThat(reaped).extracting(Reaped::id, Reaped::requeued).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(again.id(), true), org.assertj.core.groups.Tuple.tuple(last.id(), false));
        StoredRun requeued = store.find(again.id()).orElseThrow();
        assertThat(requeued.status()).isEqualTo("PENDING");
        assertThat(requeued.attempt()).isEqualTo(2);
        assertThat(requeued.errorCode()).isEqualTo("LEASE_LOST");
        StoredRun failed = store.find(last.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorCode()).isEqualTo("LEASE_LOST");
        assertThat(failed.failureEventOwed()).isTrue();
        assertThat(store.complete(again.id(), "a", 1, "SUCCEEDED", false, OUTCOME)).as("the old owner is fenced").isFalse();
        assertThat(store.claim("b", LEASE, 5, GRACE)).extracting(Claimed::id).containsExactly(again.id());
    }

    @Test
    void aPreviewWhoseLeaseRanOutIsFailedAndNeverQueuedAgain() throws Exception {
        UUID preview = UUID.randomUUID();
        store.enqueuePreview(new NewPreview(preview, null, null, "unsaved", "{}", 7L, Duration.ofMinutes(5),
                new TimeWindow(BASE, BASE.plusSeconds(3600)), null, "user"));
        store.claimOne(preview, "a", Duration.ofMillis(200), GRACE);
        Thread.sleep(300);

        store.reap(10, Duration.ZERO, Duration.ZERO);

        StoredRun failed = store.find(preview).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureEventOwed()).as("a preview raises no event").isFalse();
    }

    @Test
    void aRunPastItsTimeoutAndGraceIsAskedToStopOnce() throws Exception {
        flow(1, 1);
        NewRun run = new NewRun(UUID.randomUUID(), 1, "flow-1", 7, 1, 2, Duration.ofMillis(100),
                new TimeWindow(BASE, BASE.plusSeconds(3600)), null, "manual", "user", "user", null);
        store.enqueue(run);
        store.claim("a", LEASE, 1, Duration.ZERO);
        assertThat(store.cancelOverdue(10)).as("not yet").isZero();
        Thread.sleep(250);

        assertThat(store.cancelOverdue(10)).isEqualTo(1);
        assertThat(store.cancelOverdue(10)).isZero();
        assertThat(store.renew(List.of(run.id()), "a", LEASE)).singleElement()
                .satisfies(r -> assertThat(r.cancelRequested()).isTrue());
    }

    // ---------------------------------------------------------------- cancelling

    @Test
    void aPendingRunCanBeCancelledAndARunningOneOnlyAskedToStop() {
        flow(1, 1);
        NewRun running = run(1, 0);
        NewRun waiting = run(1, 1);
        store.enqueueAll(List.of(running, waiting));
        store.claim("a", LEASE, 5, GRACE);

        assertThat(store.cancelPending(running.id(), "CANCELLED", "no")).as("it is running").isFalse();
        assertThat(store.requestCancel(waiting.id())).as("it is not running").isFalse();
        assertThat(store.cancelPending(waiting.id(), "CANCELLED", "Cancelled by user-1.")).isTrue();
        assertThat(store.requestCancel(running.id())).isTrue();

        StoredRun cancelled = store.find(waiting.id()).orElseThrow();
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.error()).isEqualTo("Cancelled by user-1.");
        assertThat(store.claim("a", LEASE, 5, GRACE)).as("a cancelled run is not started").isEmpty();
    }

    // ---------------------------------------------------------------- failed windows and events

    @Test
    void aWindowThatFailedIsRememberedUntilARunOfItSucceeds() {
        flow(1, 2);
        NewRun failing = run(1, 0);
        NewRun redo = run(1, 0);
        store.enqueue(failing);
        store.claim("a", LEASE, 1, GRACE);
        assertThat(store.failedWindow(1, BASE)).isFalse();

        store.complete(failing.id(), "a", 1, "FAILED", true, OUTCOME);
        assertThat(store.failedWindow(1, BASE)).isTrue();
        assertThat(store.failedWindow(1, BASE.plusSeconds(3600))).isFalse();

        store.enqueue(redo);
        store.claim("a", LEASE, 1, GRACE);
        store.complete(redo.id(), "a", 1, "SUCCEEDED", false, OUTCOME);
        assertThat(store.failedWindow(1, BASE)).isFalse();
    }

    // ---------------------------------------------------------------- history

    @Test
    void anEndedRunExpiresOnceArchivedUnlessItStillOwesItsEvent() throws Exception {
        flow(1, 2);
        NewRun fine = run(1, 0);
        NewRun owing = run(1, 1);
        store.enqueueAll(List.of(fine, owing));
        store.claim("a", LEASE, 2, GRACE);
        store.complete(fine.id(), "a", 1, "SUCCEEDED", false, OUTCOME);
        store.complete(owing.id(), "a", 1, "FAILED", true, OUTCOME);
        assertThat(store.unarchived(10)).extracting(StoredRun::id).containsExactlyInAnyOrder(fine.id(), owing.id());

        store.archived(List.of(fine.id(), owing.id()));
        assertThat(store.unarchived(10)).isEmpty();
        assertThat(store.find(fine.id())).as("readable for the finished TTL").isPresent();
        Thread.sleep(1500);

        assertThat(store.find(fine.id())).as("then ClickHouse has it").isEmpty();
        assertThat(store.find(owing.id())).as("it still owes its event").isPresent();

        store.eventPaid(owing.id());
        assertThat(store.owedEvents(10)).isEmpty();
        Thread.sleep(1500);
        assertThat(store.find(owing.id())).isEmpty();
    }

    // ---------------------------------------------------------------- flows

    @Test
    void purgingAFlowForgetsItsRunsAndLeavesOthersAlone() {
        flow(1, 1);
        flow(2, 1);
        NewRun running = run(1, 0);
        NewRun waiting = run(1, 1);
        NewRun delayed = run(1, 2);
        NewRun other = run(2, 0);
        store.enqueueAll(List.of(running, waiting, delayed, other));
        store.claim("a", LEASE, 1, GRACE);
        store.claim("a", LEASE, 1, GRACE);
        store.retry(running.id(), "a", 1, Duration.ofMinutes(5), OUTCOME);

        assertThat(store.purgeFlow(1)).isEqualTo(3);

        assertThat(store.find(List.of(running.id(), waiting.id(), delayed.id()))).isEmpty();
        assertThat(store.find(other.id())).isPresent();
        assertThat(store.complete(other.id(), "a", 1, "SUCCEEDED", false, OUTCOME)).isTrue();
    }

    // ---------------------------------------------------------------- the invariants

    @Test
    void randomSequencesOfOperationsKeepTheInvariants() throws Exception {
        for (long seed = 1; seed <= 3; seed++) {
            SharedKvrocks.flush();
            Random random = new Random(seed);
            for (long f = 1; f <= 4; f++) {
                flow(f, 1 + random.nextInt(3));
            }
            List<Claimed> held = new ArrayList<>();
            List<UUID> made = new ArrayList<>();
            for (int step = 0; step < 300; step++) {
                switch (random.nextInt(10)) {
                    case 0, 1, 2 -> {
                        NewRun run = run(UUID.randomUUID(), 1 + random.nextInt(4), random.nextInt(30), 1 + random.nextInt(3));
                        store.enqueue(run);
                        made.add(run.id());
                    }
                    case 3, 4 -> held.addAll(store.claim(random.nextBoolean() ? "a" : "b", Duration.ofMillis(150),
                            1 + random.nextInt(4), GRACE));
                    case 5 -> end(held, random, (c, owner) -> store.complete(c.id(), owner, c.attempt() + (random.nextInt(8) == 0 ? 1 : 0),
                            random.nextBoolean() ? "SUCCEEDED" : "FAILED", random.nextBoolean(), OUTCOME));
                    case 6 -> end(held, random, (c, owner) -> store.retry(c.id(), owner, c.attempt(),
                            Duration.ofMillis(random.nextInt(100)), OUTCOME));
                    case 7 -> end(held, random, (c, owner) -> store.release(c.id(), owner, c.attempt()));
                    case 8 -> {
                        if (!made.isEmpty()) {
                            store.cancelPending(made.get(random.nextInt(made.size())), "CANCELLED", "no");
                        }
                        if (random.nextInt(3) == 0) {
                            store.syncFlow(new FlowLimits(1 + random.nextInt(4), "f", 7, random.nextInt(4) != 0,
                                    random.nextInt(6) != 0, 1 + random.nextInt(3)));
                        }
                    }
                    default -> {
                        Thread.sleep(random.nextInt(40));
                        store.renew(held.stream().map(Claimed::id).toList(), "a", Duration.ofMillis(150));
                        if (random.nextInt(4) == 0) {
                            Thread.sleep(160);
                            store.reap(5, Duration.ofMillis(10), Duration.ofMillis(50));
                        }
                    }
                }
                assertThat(StoreInvariants.violations()).as("after step %d of seed %d", step, seed).isEmpty();
            }
        }
    }

    private void end(List<Claimed> held, Random random, java.util.function.BiConsumer<Claimed, String> how) {
        if (held.isEmpty()) {
            return;
        }
        Claimed c = held.remove(random.nextInt(held.size()));
        how.accept(c, random.nextInt(10) == 0 ? "mallory" : "a");
    }

    @Test
    void manyThreadsClaimEachRunOnceAndNeverExceedAFlowsLimit() throws Exception {
        int flows = 16;
        int perFlow = 250;
        int total = flows * perFlow;
        int[] limits = new int[flows + 1];
        for (int f = 1; f <= flows; f++) {
            limits[f] = 1 + f % 4;
            flow(f, limits[f]);
        }
        List<NewRun> runs = new ArrayList<>();
        for (int f = 1; f <= flows; f++) {
            for (int h = 0; h < perFlow; h++) {
                runs.add(run(f, h));
            }
        }
        Collections.shuffle(runs, new Random(7));
        store.enqueueAll(runs);

        AtomicInteger[] inFlight = new AtomicInteger[flows + 1];
        for (int f = 1; f <= flows; f++) {
            inFlight[f] = new AtomicInteger();
        }
        Map<UUID, Boolean> seen = new ConcurrentHashMap<>();
        Set<String> problems = ConcurrentHashMap.newKeySet();
        AtomicInteger done = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            String owner = "worker-" + w;
            workers.add(Thread.ofVirtual().start(() -> {
                TenantContext.setTenantId("t");
                try {
                    while (done.get() < total) {
                        List<Claimed> claimed = store.claim(owner, LEASE, 1 + new Random().nextInt(4), GRACE);
                        for (Claimed c : claimed) {
                            int flow = Integer.parseInt(c.flowId().toString());
                            if (seen.put(c.id(), true) != null) {
                                problems.add("claimed twice: " + c.id());
                            }
                            if (inFlight[flow].incrementAndGet() > limits[flow]) {
                                problems.add("flow " + flow + " over its limit");
                            }
                        }
                        for (Claimed c : claimed) {
                            int flow = Integer.parseInt(c.flowId().toString());
                            inFlight[flow].decrementAndGet();
                            if (!store.complete(c.id(), owner, c.attempt(), "SUCCEEDED", false, OUTCOME)) {
                                problems.add("could not end " + c.id());
                            }
                            done.incrementAndGet();
                        }
                    }
                } finally {
                    TenantContext.clear();
                }
            }));
        }
        long until = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        for (Thread worker : workers) {
            worker.join(Math.max(1, (until - System.nanoTime()) / 1_000_000));
        }

        assertThat(problems).isEmpty();
        assertThat(seen).hasSize(total);
        assertThat(done.get()).isEqualTo(total);
        assertThat(store.unarchivedCount()).isEqualTo(total);
    }

    // ---------------------------------------------------------------- fixtures

    private void flow(long id, int maxConcurrent) {
        store.syncFlow(new FlowLimits(id, "flow-" + id, 7, true, true, maxConcurrent));
    }

    private static NewRun run(long flow, int hour) {
        return run(UUID.randomUUID(), flow, hour, 2);
    }

    private static NewRun run(UUID id, long flow, int hour, int maxAttempts) {
        Instant start = BASE.plus(Duration.ofHours(hour));
        return new NewRun(id, flow, "flow-" + flow, 7, 1, maxAttempts, Duration.ofMinutes(15),
                new TimeWindow(start, start.plus(Duration.ofHours(1))), null, "manual", "user", "user", null);
    }
}
