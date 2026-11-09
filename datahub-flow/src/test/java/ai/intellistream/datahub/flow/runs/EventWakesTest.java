// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.api.EventMatch;
import ai.intellistream.datahub.flow.store.RunStore;
import ai.intellistream.datahub.flow.store.RunStore.ScheduleRecord;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.me.MeDataSets;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventCudMessage;
import ai.intellistream.datahub.pulsar.EventObject;
import ai.intellistream.datahub.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Which events wake which flows, as their identities may read them, and how a flow catches up. */
class EventWakesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static final Instant DEPLOYED = NOW.minusSeconds(3600);
    private static final long READABLE = 7;
    private static final long HIDDEN = 8;

    private final RunStore store = mock(RunStore.class);
    private final FakeIdentity identity = new FakeIdentity();
    private EventWakes wakes;

    /** The flows' identity as datahub-api would answer it: what it may read, and what it finds. */
    private static final class FakeIdentity implements EventWakes.FlowIdentity {
        volatile MeDataSets dataSets = new MeDataSets(false, List.of(READABLE));
        volatile Optional<Instant> found = Optional.empty();
        final AtomicReference<RuntimeException> failOnce = new AtomicReference<>();
        final List<Instant> askedFrom = new ArrayList<>();

        @Override
        public MeDataSets readable(String tenantId, long flowId) {
            RuntimeException e = failOnce.getAndSet(null);
            if (e != null) {
                throw e;
            }
            return dataSets;
        }

        @Override
        public synchronized Optional<Instant> recorded(String tenantId, long flowId, EventMatch match, Instant from,
                                                       Instant to) {
            askedFrom.add(from);
            return found;
        }
    }

    @BeforeEach
    void setUp() {
        wakes = new EventWakes(null, null, store, mock(RunCoordinator.class), identity, JSON, true,
                Duration.ofMinutes(5));
        when(store.now()).thenReturn(NOW);
        Schedule alarms = new Schedule(Schedule.EVENTS, null, null, null, Duration.ofSeconds(10),
                Duration.ofSeconds(10), new EventMatch(null, List.of("alarm"), null, null, null, null,
                        List.of("agent:*")));
        Schedule cron = new Schedule(Schedule.SCHEDULE, "0 * * * * *", null, null, null);
        when(store.scheduledFlows()).thenReturn(List.of(1L, 2L));
        when(store.schedules(any())).thenReturn(List.of(record(1, alarms), record(2, cron)));
    }

    @AfterEach
    void tearDown() {
        wakes.stop();
    }

    private void refresh() {
        TenantContext.runWith("t", () -> wakes.refresh("t"));
    }

    @Test
    void aFlowSeenForTheFirstTimeIsWokenOnlyIfItsIdentityFindsAnEventSinceItsLastRun() {
        refresh();
        assertThat(identity.askedFrom).as("since the deployment, the flow having no run yet").containsExactly(DEPLOYED);
        verify(store, never()).wake(anyLong(), any());

        identity.found = Optional.of(NOW.minusSeconds(5));
        refresh();
        assertThat(identity.askedFrom).as("caught up already: not asked again").hasSize(1);
        verify(store, never()).wake(anyLong(), any());
    }

    @Test
    void anEventMissedWhileNobodyReadWakesTheFlowAtStartUp() {
        identity.found = Optional.of(NOW.minusSeconds(5));
        refresh();
        verify(store).wake(1, NOW.minusSeconds(5));
        verify(store, never()).wake(eq(2L), any());
    }

    @Test
    void aNewEventWakesTheFlowOnlyInADatasetItsIdentityMayRead() {
        refresh();
        Instant hidden = NOW.plusSeconds(3);
        Instant readable = NOW.plusSeconds(2);
        wakes.handle(message("t", EventAction.CREATE, event("alarm", "plc", hidden, HIDDEN),
                event("alarm", "plc", readable, READABLE), event("alarm", "plc", NOW.plusSeconds(4), null),
                event("info", "plc", NOW.plusSeconds(5), READABLE)));

        verify(store, timeout(2000)).wake(1, readable);
        verify(store, after(200).never()).wake(1, hidden);
        verify(store, never()).wake(1, NOW.plusSeconds(4));
    }

    @Test
    void anIdentityThatMayReadEverythingIsWokenByEventsInNoDatasetToo() {
        identity.dataSets = new MeDataSets(true, List.of());
        refresh();
        wakes.handle(message("t", EventAction.CREATE, event("alarm", "plc", NOW.plusSeconds(4), null)));

        verify(store, timeout(2000)).wake(1, NOW.plusSeconds(4));
    }

    @Test
    void aWakeTheIdentityCouldNotBeAskedForIsKeptAndMadeWithTheNextOne() {
        refresh();
        identity.failOnce.set(new RunCredentials.RunIdentityException("Keycloak could not be asked", false));
        wakes.handle(message("t", EventAction.CREATE, event("alarm", "plc", NOW.plusSeconds(2), READABLE)));
        verify(store, after(300).never()).wake(anyLong(), any());

        wakes.handle(message("t", EventAction.CREATE, event("alarm", "plc", NOW.plusSeconds(1), READABLE)));
        verify(store, timeout(2000)).wake(1, NOW.plusSeconds(2));
    }

    @Test
    void aRefusedIdentityWakesNothing() {
        refresh();
        identity.failOnce.set(new RunCredentials.RunIdentityException("refused", true));
        wakes.handle(message("t", EventAction.CREATE, event("alarm", "plc", NOW.plusSeconds(2), READABLE)));
        verify(store, after(300).never()).wake(anyLong(), any());
    }

    @Test
    void otherEventsChangesAndOtherTenantsWakeNothing() {
        refresh();
        wakes.handle(message("t", EventAction.CREATE, event("alarm", "agent:watch", NOW.plusSeconds(1), READABLE)));
        wakes.handle(message("t", EventAction.UPDATE, event("alarm", "plc", NOW.plusSeconds(1), READABLE)));
        wakes.handle(message("other", EventAction.CREATE, event("alarm", "plc", NOW.plusSeconds(1), READABLE)));

        verify(store, after(300).never()).wake(anyLong(), eq(NOW.plusSeconds(1)));
    }

    private static ScheduleRecord record(long flowId, Schedule schedule) {
        return new ScheduleRecord(flowId, "flow-" + flowId, 7, 1, 2, Duration.ofMinutes(15), "user-1",
                schedule.toJson(JSON), schedule.delay(), DEPLOYED, null, null);
    }

    private static EventCudMessage message(String tenantId, EventAction action, EventModel... events) {
        EventCudMessage message = new EventCudMessage();
        message.setTenantId(tenantId);
        message.setEventAction(action);
        message.setEventObject(EventObject.EVENT);
        message.setEvents(List.of(events));
        return message;
    }

    private static EventModel event(String type, String source, Instant recorded, Long dataSetId) {
        EventModel e = new EventModel();
        e.setType(type);
        e.setSource(source);
        e.setMetadata(Map.of());
        e.setDataSetId(dataSetId);
        e.setCreatedTime(recorded.atZone(ZoneOffset.UTC));
        return e;
    }
}
