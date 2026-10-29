// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.api.EventMatch;
import ai.intellistream.datahub.flow.store.RunStore;
import ai.intellistream.datahub.flow.store.RunStore.ScheduleRecord;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.datafilters.TimeFilter;
import ai.intellistream.datahub.models.events.EventFilter;
import ai.intellistream.datahub.models.events.EventRetreiver;
import ai.intellistream.datahub.models.me.MeDataSets;
import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventCudMessage;
import ai.intellistream.datahub.pulsar.EventObject;
import ai.intellistream.datahub.pulsar.TopicNames;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.client.MeService;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionMode;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.api.schema.SchemaDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wakes the flows whose trigger is {@code events}: reads every new event off the platform's shared
 * events topic and gives each flow whose trigger matches it a tick ({@link RunStore#wake}), which the
 * ticker makes into a run as it does a cron tick. The event is a signal: a run reads events only if the
 * flow has a step that does, through datahub-api as the run's identity.
 *
 * <p><b>Only what the flow may read wakes it.</b> The topic carries every event before any access check,
 * so a flow that takes the event as a signal alone would otherwise learn of events in datasets its
 * identity may not read. An event wakes a flow only when it is in a dataset the flow's identity may read,
 * as datahub-api answers for that identity ({@code /me/datasets}, kept for {@code access-ttl}); an event
 * in no dataset only for an identity that may read everything, as the api's own filter does.
 *
 * <p><b>Non-durable.</b> Every instance reads the topic on a subscription of its own that Pulsar keeps
 * no backlog for, so a flow service that is down or slow can never hold back event ingest, which a
 * durable subscription's backlog would, for every tenant at once. An event that arrived while no instance
 * was reading is not lost, only unannounced: each instance asks datahub-api, as the flow, whether a
 * matching event was recorded since the flow's last run for every events flow it has not caught up yet
 * ({@link #refresh}), at startup and when a flow is deployed elsewhere, and wakes it if so. A wake made
 * twice, by two instances, is the same tick.
 *
 * <p><b>One tenant never stalls another.</b> The reading thread only matches, against an index of each
 * tenant's events flows held here; the access checks and the wakes go out on a thread of the tenant's
 * own, and a tenant whose KVRocks or identity does not answer keeps its wakes, at most one per flow and
 * dataset, until it does.
 */
@Slf4j
@Component
public class EventWakes {

    private final PulsarClient pulsar;
    private final TopicNames topics;
    private final RunStore store;
    private final RunCoordinator coordinator;
    private final FlowIdentity identity;
    private final JsonMapper json;
    private final boolean enabled;
    private final Duration accessTtl;

    /** Each tenant's events flows: the flow and what wakes it. */
    private final Map<String, Map<Long, Schedule>> index = new ConcurrentHashMap<>();
    /** Each tenant's events flows that have been asked about what they missed while nobody read for them. */
    private final Map<String, Set<Long>> caughtUp = new ConcurrentHashMap<>();
    /**
     * Each tenant's wakes not yet made: flow → dataset (0 for none) → the latest recording time of an
     * event there that matched it. By dataset, since that decides whether the flow may be woken by it.
     */
    private final Map<String, Map<Long, Map<Long, Long>>> pending = new ConcurrentHashMap<>();
    /** What each flow's identity may read, by tenant and flow, and until when that answer stands. */
    private final Map<String, Readable> readable = new ConcurrentHashMap<>();
    private final Set<String> draining = ConcurrentHashMap.newKeySet();
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Consumer<EventCudMessage> consumer;
    private volatile boolean stopping;

    /**
     * What datahub-api answers a flow's identity: the datasets it may read, and the latest recording time
     * of an event it may read that the trigger matches. Asked under the flow's tenant.
     */
    interface FlowIdentity {
        MeDataSets readable(String tenantId, long flowId);

        Optional<Instant> recorded(String tenantId, long flowId, EventMatch match, Instant from, Instant to);
    }

    /** What an identity may read, as the api last answered, and until when that answer is kept. */
    private record Readable(boolean all, Set<Long> ids, long untilNanos) {
        static Readable of(MeDataSets answer, long untilNanos) {
            return new Readable(answer.all(), answer.ids() == null ? Set.of() : Set.copyOf(answer.ids()), untilNanos);
        }

        /** An event in no dataset (0) only for an identity that may read everything, as the api's filter does. */
        boolean covers(long dataSetId) {
            return all || dataSetId != 0 && ids.contains(dataSetId);
        }
    }

    @Autowired
    public EventWakes(PulsarClient pulsar, TopicNames topics, RunStore store, RunCoordinator coordinator,
                      RunCredentials credentials, JsonMapper json,
                      @Value("${datahub.api.url:http://localhost:8081}") String apiBaseUrl,
                      @Value("${datahub.flow.runs.request-timeout:PT30S}") Duration requestTimeout,
                      @Value("${datahub.flow.events.enabled:true}") boolean enabled,
                      @Value("${datahub.flow.events.access-ttl:PT5M}") Duration accessTtl) {
        this(pulsar, topics, store, coordinator, new ApiIdentity(credentials, apiBaseUrl, requestTimeout), json,
                enabled, accessTtl);
    }

    EventWakes(PulsarClient pulsar, TopicNames topics, RunStore store, RunCoordinator coordinator,
               FlowIdentity identity, JsonMapper json, boolean enabled, Duration accessTtl) {
        this.pulsar = pulsar;
        this.topics = topics;
        this.store = store;
        this.coordinator = coordinator;
        this.identity = identity;
        this.json = json;
        this.enabled = enabled;
        this.accessTtl = accessTtl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        refreshAll();
    }

    /**
     * Subscribes if not yet, then reads each tenant's events flows anew. Subscribing comes first, so that
     * the wake given to a flow seen for the first time comes after the reading began, and nothing falls
     * between the two.
     */
    @Scheduled(initialDelayString = "${datahub.flow.runs.sweep-interval:PT10S}",
            fixedDelayString = "${datahub.flow.runs.sweep-interval:PT10S}")
    public void refreshAll() {
        if (!enabled || stopping || !subscribed()) {
            return;
        }
        List<String> tenants = coordinator.flowTenants();
        index.keySet().retainAll(tenants);
        caughtUp.keySet().retainAll(tenants);
        for (String tenantId : tenants) {
            if (refreshing.add(tenantId)) {
                executor.execute(() -> {
                    try {
                        TenantContext.runWith(tenantId, () -> refresh(tenantId));
                    } catch (RuntimeException e) {
                        log.warn("Could not read the events flows of tenant {}; trying again next round: {}",
                                tenantId, e.getMessage());
                    } finally {
                        refreshing.remove(tenantId);
                    }
                });
            }
            if (pending.containsKey(tenantId)) {
                drain(tenantId);
            }
        }
    }

    private boolean subscribed() {
        if (consumer != null) {
            return true;
        }
        try {
            Consumer<EventCudMessage> c = pulsar.newConsumer(Schema.AVRO(SchemaDefinition.<EventCudMessage>builder()
                            .withJSR310ConversionEnabled(false).withPojo(EventCudMessage.class).build()))
                    .topic(topics.getEventsTopicName())
                    .subscriptionName("intellistream-flow-wakes-" + UUID.randomUUID())
                    .subscriptionMode(SubscriptionMode.NonDurable)
                    .subscriptionType(SubscriptionType.Exclusive)
                    .subscriptionInitialPosition(SubscriptionInitialPosition.Latest)
                    .subscribe();
            consumer = c;
            Thread.ofVirtual().name("flow-event-wakes").start(() -> consume(c));
            log.info("Reading {} to wake event-triggered flows", topics.getEventsTopicName());
            return true;
        } catch (PulsarClientException | RuntimeException e) {
            log.warn("Could not subscribe to the events topic; event-triggered flows wait for the next try: {}",
                    e.getMessage());
            return false;
        }
    }

    /**
     * Reads one tenant's events flows from its run store, then catches up each flow not caught up yet:
     * datahub-api is asked, as the flow, for an event its trigger matches recorded since its last run, and
     * the flow is woken for it. Reading began before this, so what is recorded from now on wakes it as it
     * comes. A flow that cannot be asked now is asked again at the next refresh.
     */
    void refresh(String tenantId) {
        Map<Long, Schedule> now = new HashMap<>();
        Map<Long, ScheduleRecord> records = new HashMap<>();
        for (ScheduleRecord r : store.schedules(store.scheduledFlows())) {
            Schedule schedule = Schedule.fromJson(json, r.trigger());
            if (schedule.evented()) {
                now.put(r.flowId(), schedule);
                records.put(r.flowId(), r);
            }
        }
        index.put(tenantId, now);
        Set<Long> done = caughtUp.computeIfAbsent(tenantId, id -> ConcurrentHashMap.newKeySet());
        done.retainAll(now.keySet());
        Instant until = store.now();
        for (Map.Entry<Long, Schedule> flow : now.entrySet()) {
            long flowId = flow.getKey();
            if (done.contains(flowId)) {
                continue;
            }
            ScheduleRecord r = records.get(flowId);
            Instant from = r.lastTick() != null ? r.lastTick() : r.deployedAt();
            try {
                identity.recorded(tenantId, flowId, flow.getValue().match(), from, until)
                        .ifPresent(at -> store.wake(flowId, at));
                done.add(flowId);
            } catch (RunCredentials.RunIdentityException e) {
                log.info("Flow {} of tenant {} could not be caught up as its identity: {}", flowId, tenantId,
                        e.getMessage());
                if (e.rejected()) {
                    // It gets no runs until it is deployed afresh, which makes it another flow here.
                    done.add(flowId);
                }
            } catch (RuntimeException e) {
                log.warn("Flow {} of tenant {} could not be caught up; trying again next round: {}", flowId,
                        tenantId, e.getMessage());
            }
        }
    }

    private void consume(Consumer<EventCudMessage> c) {
        while (!stopping) {
            Message<EventCudMessage> message;
            try {
                message = c.receive();
            } catch (PulsarClientException e) {
                if (stopping) {
                    return;
                }
                log.warn("Reading the events topic failed: {}", e.getMessage());
                pause();
                continue;
            }
            try {
                handle(message.getValue());
            } catch (RuntimeException e) {
                log.warn("Skipping events message {}: {}", message.getMessageId(), e.getMessage());
            }
            c.acknowledgeAsync(message);
        }
    }

    /** Matches a message's new events against its tenant's events flows; never waits on a tenant. */
    void handle(EventCudMessage message) {
        if (message.getEventAction() != EventAction.CREATE || message.getEventObject() != EventObject.EVENT
                || message.getTenantId() == null) {
            return;
        }
        String tenantId = message.getTenantId();
        Map<Long, Schedule> flows = index.get(tenantId);
        if (flows == null || flows.isEmpty() || message.getEvents() == null) {
            return;
        }
        Map<Long, Map<Long, Long>> woken = new HashMap<>();
        for (EventModel event : message.getEvents()) {
            long recorded = event.getCreatedTime() == null ? System.currentTimeMillis()
                    : event.getCreatedTime().toInstant().toEpochMilli();
            long dataSet = event.getDataSetId() == null ? 0 : event.getDataSetId();
            flows.forEach((flowId, schedule) -> {
                if (schedule.match() == null || schedule.match().matches(event)) {
                    woken.computeIfAbsent(flowId, id -> new HashMap<>()).merge(dataSet, recorded, Math::max);
                }
            });
        }
        if (woken.isEmpty()) {
            return;
        }
        merge(tenantId, woken);
        drain(tenantId);
    }

    /** Adds wakes to a tenant's pending ones, keeping the latest per flow and dataset. */
    private void merge(String tenantId, Map<Long, Map<Long, Long>> wakes) {
        pending.compute(tenantId, (id, now) -> {
            Map<Long, Map<Long, Long>> out = now == null ? new HashMap<>() : now;
            wakes.forEach((flowId, byDataSet) -> byDataSet.forEach((dataSet, recorded) ->
                    out.computeIfAbsent(flowId, f -> new HashMap<>()).merge(dataSet, recorded, Math::max)));
            return out;
        });
    }

    /** Makes a tenant's pending wakes on a thread of its own, unless one is at it already. */
    private void drain(String tenantId) {
        if (!draining.add(tenantId)) {
            return;
        }
        executor.execute(() -> {
            try {
                while (true) {
                    Map<Long, Map<Long, Long>> wakes = pending.remove(tenantId);
                    if (wakes == null) {
                        draining.remove(tenantId);
                        // A wake added after the look and before the removal would wait for the next event.
                        if (!pending.containsKey(tenantId) || !draining.add(tenantId)) {
                            return;
                        }
                        continue;
                    }
                    wake(tenantId, wakes);
                }
            } catch (RuntimeException e) {
                draining.remove(tenantId);
                log.warn("Could not wake the event-triggered flows of tenant {}; trying again at the next event "
                        + "or refresh: {}", tenantId, e.getMessage());
            }
        });
    }

    /**
     * Wakes each flow for the latest of its events in a dataset its identity may read. A flow whose
     * identity was refused is not woken; one whose identity or KVRocks could not be asked keeps its wakes
     * for the next try, and the first such failure ends this drain.
     */
    private void wake(String tenantId, Map<Long, Map<Long, Long>> wakes) {
        Map<Long, Map<Long, Long>> left = new HashMap<>();
        RuntimeException failed = null;
        for (Map.Entry<Long, Map<Long, Long>> flow : wakes.entrySet()) {
            long flowId = flow.getKey();
            if (failed != null) {
                left.put(flowId, flow.getValue());
                continue;
            }
            try {
                TenantContext.runWith(tenantId, () -> {
                    Readable may = readable(tenantId, flowId);
                    flow.getValue().entrySet().stream().filter(e -> may.covers(e.getKey()))
                            .mapToLong(Map.Entry::getValue).max()
                            .ifPresent(at -> store.wake(flowId, Instant.ofEpochMilli(at)));
                });
            } catch (RunCredentials.RunIdentityException e) {
                if (!e.rejected()) {
                    failed = e;
                    left.put(flowId, flow.getValue());
                }
            } catch (RuntimeException e) {
                failed = e;
                left.put(flowId, flow.getValue());
            }
        }
        if (failed != null) {
            merge(tenantId, left);
            throw failed;
        }
    }

    /** What the flow's identity may read: as last asked, unless that is older than {@code access-ttl}. */
    private Readable readable(String tenantId, long flowId) {
        String key = tenantId + "/" + flowId;
        Readable known = readable.get(key);
        if (known != null && System.nanoTime() - known.untilNanos() < 0) {
            return known;
        }
        Readable fresh = Readable.of(identity.readable(tenantId, flowId), System.nanoTime() + accessTtl.toNanos());
        readable.put(key, fresh);
        return fresh;
    }

    /** The flow's identity asking datahub-api, with the tokens a run of the flow would have. */
    private static final class ApiIdentity implements FlowIdentity {
        private final RunCredentials credentials;
        private final String apiBaseUrl;
        private final Duration requestTimeout;
        private final HttpClient http = HttpClient.newHttpClient();

        ApiIdentity(RunCredentials credentials, String apiBaseUrl, Duration requestTimeout) {
            this.credentials = credentials;
            this.apiBaseUrl = apiBaseUrl;
            this.requestTimeout = requestTimeout;
        }

        private DatahubClient client(String tenantId, long flowId) {
            return DatahubClient.create(DatahubConfig.builder().baseUrl(apiBaseUrl)
                    .tokenSupplier(credentials.tokens(tenantId, flowId)).requestTimeout(requestTimeout).build(), http);
        }

        @Override
        public MeDataSets readable(String tenantId, long flowId) {
            return client(tenantId, flowId).me().datasets(MeService.Access.READ);
        }

        @Override
        public Optional<Instant> recorded(String tenantId, long flowId, EventMatch match, Instant from, Instant to) {
            EventFilter filter = match.filter();
            TimeFilter recorded = new TimeFilter();
            recorded.setMin(from.atZone(ZoneOffset.UTC));
            recorded.setMax(to.atZone(ZoneOffset.UTC));
            filter.setCreatedTime(recorded);
            EventRetreiver request = new EventRetreiver();
            request.setFilter(filter);
            List<String> exclusions = EventMatch.exclusions(match.excludeSource());
            request.setAdvancedFilter(exclusions.isEmpty() ? null : String.join(" AND ", exclusions));
            request.setLimit(1);
            List<EventModel> found = client(tenantId, flowId).events().filter(request).getItems();
            return found == null || found.isEmpty() || found.getFirst().getCreatedTime() == null ? Optional.empty()
                    : Optional.of(found.getFirst().getCreatedTime().toInstant());
        }
    }

    private void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        Consumer<EventCudMessage> c = consumer;
        if (c != null) {
            c.closeAsync();
        }
        executor.shutdownNow();
    }
}
