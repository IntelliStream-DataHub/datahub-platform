// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.e2e;

import ai.intellistream.datahub.api.responses.DataRetriever;
import ai.intellistream.datahub.api.responses.DatapointsCollection;
import ai.intellistream.datahub.e2e.FlowStack.Client;
import ai.intellistream.datahub.e2e.FlowStack.Response;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.timeseries.Datapoint;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flows on the live platform, the way a tenant would use them: dozens of scheduled flows at once,
 * two tenants that must not see each other, and a flow service that dies with runs in hand.
 *
 * <p>Each flow reads one series over the last minute and writes it to a second series, so a
 * tick's outcome is visible in the data as well as in the run. Needs the stack of README.md plus
 * the flow service: either one already running ({@code DATAHUB_FLOW_URL}) or the boot jar
 * ({@code ./gradlew :datahub-flow:bootJar}), which the tests then start themselves, with its loops
 * sped up, and which the crash test kills. Without the jar the crash test is skipped; without a
 * second tenant's client the isolation test is.
 */
@Tag("flows")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlowsE2eIT {

    private static final Duration TICK = Duration.ofMinutes(1);
    private static final String RUN_CLIENT = "e2e";

    private Client one;
    private Client two;
    private DatahubClient sdkOne;
    private DatahubClient sdkTwo;
    private FlowStack.Service service;
    private String flowUrl;
    private String runId;
    private String dataSetOne;
    private String dataSetTwo;
    private final Map<Client, List<String>> flowsCreated = new HashMap<>();
    private final Map<DatahubClient, List<String>> seriesCreated = new HashMap<>();
    private final Map<DatahubClient, Long> dataSetsCreated = new HashMap<>();

    @BeforeAll
    void setUp() throws Exception {
        LiveStack.requireReachable();
        runId = "e2e_" + Long.toString(System.currentTimeMillis(), 36);
        one = FlowStack.tenantOne();
        two = FlowStack.tenantTwo();
        FlowStack.grantFlowGroups(one);
        if (two != null) {
            FlowStack.grantFlowGroups(two);
        }

        flowUrl = LiveStack.env("DATAHUB_FLOW_URL", null);
        if (flowUrl == null) {
            Path jar = FlowStack.Service.jar();
            Assumptions.assumeTrue(jar != null, "Neither DATAHUB_FLOW_URL nor a flow service jar "
                    + "(./gradlew :datahub-flow:bootJar, or DATAHUB_FLOW_JAR) is available");
            service = new FlowStack.Service(jar, List.of("--datahub.flow.runs.max-running=16"));
            service.start();
            flowUrl = service.url();
        }
        Response catalog = FlowStack.get(flowUrl + "/catalog/processors", one);
        Assumptions.assumeTrue(catalog.status() == 200, () -> "The flow service at " + flowUrl + " answered "
                + catalog.status() + " for " + one.clientId + ": " + catalog.body());

        sdkOne = LiveStack.client();
        dataSetOne = dataSet(sdkOne, one);
        registerRunClient(one, LiveStack.env("DATAHUB_CLIENT_ID", null), LiveStack.env("DATAHUB_CLIENT_SECRET", null));
        if (two != null) {
            sdkTwo = new DatahubClient(DatahubConfig.builder().baseUrl(LiveStack.baseUrl())
                    .clientCredentials(two.clientId, LiveStack.env("DATAHUB_OTHER_CLIENT_SECRET", null),
                            LiveStack.env("DATAHUB_TOKEN_URI", null))
                    .scope(LiveStack.env("DATAHUB_SCOPE", "openid organization:*")).build());
            dataSetTwo = dataSet(sdkTwo, two);
            registerRunClient(two, two.clientId, LiveStack.env("DATAHUB_OTHER_CLIENT_SECRET", null));
        }
    }

    @AfterAll
    void tearDown() {
        if (one == null || flowUrl == null) {
            // Set-up skipped the class before anything was made.
            if (service != null) {
                service.stop();
            }
            return;
        }
        if (service != null && !service.alive()) {
            try {
                service.start();
            } catch (Exception e) {
                System.err.println("could not restart the flow service for cleanup: " + e);
            }
        }
        flowsCreated.forEach((client, ids) -> ids.forEach(id -> {
            Response deleted = FlowStack.delete(flowUrl + "/flows/" + id, client);
            if (deleted.status() != 204 && deleted.status() != 404) {
                System.err.println("cleanup: DELETE /flows/" + id + " answered " + deleted.status());
            }
        }));
        seriesCreated.forEach((sdk, ids) -> {
            try {
                sdk.timeseries().delete(ids.stream().map(IdCollection::createFromExternalId).toList());
            } catch (RuntimeException e) {
                System.err.println("cleanup: deleting series " + ids + " failed: " + e.getMessage());
            }
        });
        dataSetsCreated.forEach((sdk, id) -> {
            try {
                sdk.datasets().delete(List.of(IdCollection.createFromId(id)));
            } catch (RuntimeException e) {
                System.err.println("cleanup: deleting dataset " + id + " failed: " + e.getMessage());
            }
        });
        for (Client client : two == null ? List.of(one) : List.of(one, two)) {
            FlowStack.delete(FlowStack.apiUrl() + "/tenant/settings/flow/clients/" + RUN_CLIENT, client);
        }
        if (service != null) {
            service.stop();
        }
    }

    // ---------------------------------------------------------------- scale

    @Test
    @Order(1)
    @DisplayName("Dozens of scheduled flows each run once per tick, and their output lands")
    void manyScheduledFlowsAllRunOnEveryTick() {
        int flows = LiveStack.envInt("DATAHUB_E2E_FLOWS", 25);
        Instant now = Instant.now();
        List<String> sources = sources(sdkOne, dataSetOne, "scale", flows, now.minus(Duration.ofMinutes(2)),
                now.plus(Duration.ofMinutes(8)));
        List<String> ids = new ArrayList<>();
        for (String source : sources) {
            ids.add(scheduledFlow(one, dataSetOne, source));
        }
        Instant deployed = Instant.now();
        Instant firstTick = deployed.truncatedTo(ChronoUnit.MINUTES).plus(TICK);
        Instant secondTick = firstTick.plus(TICK);
        System.out.printf("scale: %d flows deployed at %s; ticks at %s and %s%n", flows, deployed, firstTick, secondTick);

        Map<String, List<JsonNode>> runs = new LinkedHashMap<>();
        await(() -> {
            runs.clear();
            for (String id : ids) {
                List<JsonNode> succeeded = runsOf(one, id).stream()
                        .filter(r -> "SUCCEEDED".equals(r.path("status").asString())).toList();
                if (succeeded.stream().map(r -> r.path("window").path("end").asString()).distinct().count() < 2) {
                    return false;
                }
                runs.put(id, succeeded);
            }
            return true;
        }, Duration.between(Instant.now(), secondTick).plus(Duration.ofMinutes(4)),
                () -> "not every flow has two succeeded ticks; " + summary(ids));

        List<Long> lagMs = new ArrayList<>();
        for (String id : ids) {
            List<JsonNode> all = runsOf(one, id);
            assertThat(all).extracting(r -> r.path("status").asString()).doesNotContain("FAILED");
            assertThat(all.stream().filter(r -> !"PENDING".equals(r.path("status").asString()))
                    .map(r -> r.path("window").path("end").asString()).toList())
                    .as("each tick of %s ran once", id).doesNotHaveDuplicates();
            assertThat(all).allSatisfy(r -> assertThat(r.path("attempt").asInt()).isEqualTo(1));
            for (JsonNode r : runs.get(id)) {
                lagMs.add(Duration.between(Instant.parse(r.path("window").path("end").asString()),
                        Instant.parse(r.path("startedAt").asString())).toMillis());
            }
        }
        lagMs.sort(null);
        System.out.printf("scale: %d runs of %d flows succeeded; tick-to-start lag p50 %d ms, p90 %d ms, max %d ms%n",
                lagMs.size(), flows, lagMs.get(lagMs.size() / 2), lagMs.get(lagMs.size() * 9 / 10), lagMs.getLast());

        // The data moved: the output series holds what the two windows held.
        for (int i = 0; i < Math.min(3, sources.size()); i++) {
            String source = sources.get(i);
            List<JsonNode> succeeded = runs.get(ids.get(i));
            Instant from = succeeded.stream().map(r -> Instant.parse(r.path("window").path("start").asString()))
                    .min(Instant::compareTo).orElseThrow();
            Instant to = succeeded.stream().map(r -> Instant.parse(r.path("window").path("end").asString()))
                    .max(Instant::compareTo).orElseThrow();
            int written = points(sdkOne, source + "_out", from, to);
            assertThat(written).as("%s_out holds the two windows' points", source).isGreaterThanOrEqualTo(100);
        }
        // And the run's records reached its tenant's ClickHouse, readable through the service.
        JsonNode sample = runs.values().iterator().next().getFirst();
        await(() -> FlowStack.get(flowUrl + "/runs/" + sample.path("id").asString() + "/records", one)
                .items().size() > 0, Duration.ofMinutes(2), () -> "no records for run " + sample.path("id"));
    }

    // ---------------------------------------------------------------- tenants

    @Test
    @Order(2)
    @DisplayName("A tenant sees nothing of another tenant's flows and runs, under the same names")
    void aTenantSeesNothingOfAnotherTenant() {
        Assumptions.assumeTrue(two != null, "DATAHUB_OTHER_CLIENT_ID/SECRET are not set; no second tenant");
        String shared = runId + "_shared";
        Instant now = Instant.now();
        String sourceOne = sources(sdkOne, dataSetOne, "iso1", 1, now.minus(Duration.ofMinutes(3)), now).getFirst();
        String sourceTwo = sources(sdkTwo, dataSetTwo, "iso2", 1, now.minus(Duration.ofMinutes(3)), now).getFirst();

        // Tenant one's flow, deployed and with a run.
        created(one, FlowStack.post(flowUrl + "/flows", one, manual(shared, dataSetOne, sourceOne)), shared);
        assertThat(FlowStack.put(flowUrl + "/flows/" + shared + "/deployment", one,
                FlowStack.json(FlowStack.map("runAs", RUN_CLIENT))).status()).isEqualTo(200);
        Response started = FlowStack.post(flowUrl + "/flows/" + shared + "/runs", one, FlowStack.json(FlowStack.map(
                "window", FlowStack.map("start", now.minus(Duration.ofMinutes(2)).toString(),
                        "end", now.minus(Duration.ofMinutes(1)).toString()))));
        assertThat(started.status()).as("%s", started.body()).isEqualTo(201);
        String run = started.body().path("id").asString();
        await(() -> "SUCCEEDED".equals(FlowStack.get(flowUrl + "/runs/" + run, one).body().path("status").asString()),
                Duration.ofMinutes(3), () -> "run " + run + " did not succeed: " + FlowStack.get(flowUrl + "/runs/" + run, one).body());

        // Tenant two: the flow and the run do not exist, by any route.
        assertThat(FlowStack.get(flowUrl + "/flows?limit=1000", two).items())
                .extracting(f -> f.path("externalId").asString()).doesNotContain(shared);
        assertThat(FlowStack.get(flowUrl + "/flows/" + shared, two).status()).isEqualTo(404);
        assertThat(FlowStack.get(flowUrl + "/flows/" + shared + "/deployment", two).status()).isEqualTo(404);
        assertThat(FlowStack.put(flowUrl + "/flows/" + shared + "/deployment", two,
                FlowStack.json(FlowStack.map("enabled", false))).status()).isEqualTo(404);
        assertThat(FlowStack.post(flowUrl + "/flows/" + shared + "/runs", two, null).status()).isEqualTo(404);
        assertThat(FlowStack.delete(flowUrl + "/flows/" + shared, two).status()).isEqualTo(404);
        assertThat(FlowStack.get(flowUrl + "/runs/" + run, two).status()).isEqualTo(404);
        assertThat(FlowStack.post(flowUrl + "/runs/" + run + "/cancel", two, null).status()).isEqualTo(404);
        assertThat(FlowStack.post(flowUrl + "/runs/" + run + "/rerun", two, null).status()).isEqualTo(404);
        assertThat(FlowStack.get(flowUrl + "/runs/" + run + "/records", two).status()).isEqualTo(404);
        assertThat(FlowStack.get(flowUrl + "/runs?limit=1000", two).items())
                .extracting(r -> r.path("id").asString()).doesNotContain(run);

        // Tenant two may use the same name: it is a different flow, with its own runs.
        created(two, FlowStack.post(flowUrl + "/flows", two, manual(shared, dataSetTwo, sourceTwo)), shared);
        JsonNode twos = FlowStack.get(flowUrl + "/flows/" + shared, two).body();
        JsonNode ones = FlowStack.get(flowUrl + "/flows/" + shared, one).body();
        assertThat(twos.path("dataSet").asString()).isEqualTo(dataSetTwo).isNotEqualTo(ones.path("dataSet").asString());
        assertThat(twos.path("createdBy").asString()).isNotEqualTo(ones.path("createdBy").asString());
        assertThat(FlowStack.get(flowUrl + "/runs?flow=" + shared + "&limit=100", two).items()).isEmpty();
        assertThat(FlowStack.get(flowUrl + "/runs?flow=" + shared + "&limit=100", one).items())
                .extracting(r -> r.path("id").asString()).containsExactly(run);
        assertThat(FlowStack.get(flowUrl + "/flows/" + shared + "/deployment", two).status()).isEqualTo(404);

        // Still one's, still untouched, after two's attempts.
        assertThat(FlowStack.get(flowUrl + "/flows/" + shared + "/deployment", one).body().path("enabled").asBoolean())
                .isTrue();
        assertThat(FlowStack.get(flowUrl + "/runs/" + run, one).body().path("status").asString()).isEqualTo("SUCCEEDED");
    }

    // ---------------------------------------------------------------- crashes

    @Test
    @Order(3)
    @DisplayName("A flow service killed with runs in hand loses no tick: they are rerun, and the missed ones caught up")
    void aKilledFlowServiceLosesNoScheduledTick() throws Exception {
        Assumptions.assumeTrue(service != null, "The flow service is not this test's to kill (DATAHUB_FLOW_URL is set)");
        int flows = LiveStack.envInt("DATAHUB_E2E_CRASH_FLOWS", 10);
        Instant now = Instant.now();
        List<String> sources = sources(sdkOne, dataSetOne, "crash", flows, now.minus(Duration.ofMinutes(2)),
                now.plus(Duration.ofMinutes(12)));
        List<String> ids = new ArrayList<>();
        for (String source : sources) {
            ids.add(scheduledFlow(one, dataSetOne, source));
        }
        Set<String> idSet = Set.copyOf(ids);
        Instant firstTick = Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(TICK);

        // Wait for the first tick's runs to be in hand, then pull the plug.
        List<JsonNode> inHand = new ArrayList<>();
        await(() -> {
            inHand.clear();
            for (JsonNode r : FlowStack.get(flowUrl + "/runs?limit=200", one).items()) {
                if (idSet.contains(r.path("flow").asString()) && "RUNNING".equals(r.path("status").asString())) {
                    inHand.add(r);
                }
            }
            return !inHand.isEmpty();
        }, Duration.between(Instant.now(), firstTick).plus(Duration.ofMinutes(2)), Duration.ofMillis(200),
                () -> "no run started");
        service.kill();
        Instant killed = Instant.now();
        System.out.printf("crash: killed the flow service at %s with %d runs in hand%n", killed, inHand.size());

        // Down across at least one whole tick, so that a tick is missed outright.
        Instant back = killed.truncatedTo(ChronoUnit.MINUTES).plus(TICK).plus(Duration.ofSeconds(20));
        FlowStack.sleep(Math.max(0, Duration.between(Instant.now(), back).toMillis()));
        service.start();
        flowUrl = service.url();
        Instant restarted = Instant.now();

        // Every tick up to the minute before now has run once; the ones in hand at the kill ran again.
        await(() -> ids.stream().allMatch(id -> covered(id, firstTick, Instant.now().truncatedTo(ChronoUnit.MINUTES)
                .minus(TICK)) != null), Duration.ofMinutes(5), () -> "not every tick is covered; " + summary(ids));
        Instant recovered = Instant.now();

        int takenOver = 0;
        int ticks = 0;
        for (String id : ids) {
            List<JsonNode> all = runsOf(one, id);
            Map<String, JsonNode> byWindow = covered(id, firstTick, Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(TICK));
            assertThat(byWindow).as("%s: every tick since %s ran, each once", id, firstTick).isNotNull();
            ticks += byWindow.size();
            assertThat(all).extracting(r -> r.path("status").asString()).doesNotContain("FAILED");
            for (JsonNode r : all) {
                if (r.path("attempt").asInt() > 1) {
                    takenOver++;
                }
            }
        }
        Set<String> inHandIds = new TreeSet<>();
        inHand.forEach(r -> inHandIds.add(r.path("id").asString()));
        for (String id : inHandIds) {
            JsonNode after = FlowStack.get(flowUrl + "/runs/" + id, one).body();
            assertThat(after.path("status").asString()).as("run %s that was in hand", id).isEqualTo("SUCCEEDED");
            assertThat(after.path("attempt").asInt()).as("run %s ran again after the kill", id).isGreaterThan(1);
        }
        System.out.printf("crash: %d ticks of %d flows all ran once across a kill at %s and a restart at %s;"
                        + " %d runs were taken over on a second attempt; every tick was covered %d s after the restart%n",
                ticks, flows, killed, restarted, takenOver, Duration.between(restarted, recovered).toSeconds());
        assertThat(takenOver).isGreaterThanOrEqualTo(inHand.size());
    }

    // ---------------------------------------------------------------- flows

    private String scheduledFlow(Client as, String dataSet, String source) {
        String id = source + "_flow";
        created(as, FlowStack.post(flowUrl + "/flows", as, definition(id, dataSet, source,
                FlowStack.map("type", "schedule", "cron", "0 * * * * *", "timezone", "UTC", "window", "PT1M"))), id);
        Response deployed = FlowStack.put(flowUrl + "/flows/" + id + "/deployment", as,
                FlowStack.json(FlowStack.map("runAs", RUN_CLIENT)));
        assertThat(deployed.status()).as("deploying %s: %s", id, deployed.body()).isEqualTo(200);
        return id;
    }

    private String manual(String id, String dataSet, String source) {
        return definition(id, dataSet, source, FlowStack.map("type", "manual", "window", "PT1M"));
    }

    /** Reads {@code source} over the window and writes it to {@code source_out}. */
    private static String definition(String id, String dataSet, String source, Map<String, Object> trigger) {
        return FlowStack.json(FlowStack.map(
                "schemaVersion", 1, "externalId", id, "dataSet", dataSet, "name", id,
                "processors", List.of(
                        FlowStack.map("id", "src", "type", "read-timeseries",
                                "properties", FlowStack.map("timeseries", source)),
                        FlowStack.map("id", "out", "type", "write-timeseries",
                                "properties", FlowStack.map("target", source + "_out", "create", true))),
                "connections", List.of(FlowStack.map("from", "src", "relationship", "success", "to", "out")),
                "trigger", trigger,
                "execution", FlowStack.map("maxConcurrentRuns", 1, "maxAttempts", 2, "timeout", "PT5M")));
    }

    private void created(Client as, Response response, String id) {
        assertThat(response.status()).as("creating flow %s: %s", id, response.body()).isEqualTo(201);
        flowsCreated.computeIfAbsent(as, c -> new ArrayList<>()).add(id);
    }

    private List<JsonNode> runsOf(Client as, String flow) {
        Response response = FlowStack.get(flowUrl + "/runs?flow=" + flow + "&limit=200", as);
        assertThat(response.status()).as("GET /runs?flow=%s: %s", flow, response.body()).isEqualTo(200);
        List<JsonNode> out = new ArrayList<>();
        response.items().forEach(out::add);
        return out;
    }

    /**
     * The flow's succeeded run for each whole-minute tick in {@code [first, last]}, by window end;
     * null when a tick has none, or ran more than once.
     */
    private Map<String, JsonNode> covered(String flow, Instant first, Instant last) {
        Map<String, JsonNode> byWindow = new LinkedHashMap<>();
        for (JsonNode r : runsOf(one, flow)) {
            if (!"SUCCEEDED".equals(r.path("status").asString())) {
                continue;
            }
            if (byWindow.put(r.path("window").path("end").asString(), r) != null) {
                return null;
            }
        }
        for (Instant tick = first; !tick.isAfter(last); tick = tick.plus(TICK)) {
            if (!byWindow.containsKey(tick.toString())) {
                return null;
            }
        }
        return byWindow;
    }

    private String summary(List<String> flows) {
        Map<String, Integer> byStatus = new java.util.TreeMap<>();
        for (String flow : flows) {
            for (JsonNode r : runsOf(one, flow)) {
                byStatus.merge(r.path("status").asString() + (r.path("errorCode").isNull() ? ""
                        : " " + r.path("errorCode").asString()), 1, Integer::sum);
            }
        }
        return "runs by status: " + byStatus;
    }

    // ---------------------------------------------------------------- data

    private String dataSet(DatahubClient sdk, Client as) {
        DataSetModel model = new DataSetModel();
        model.setExternalId(runId + "_" + as.organization());
        model.setName("Flows e2e " + runId);
        var created = sdk.datasets().create(List.of(model)).getItems().iterator().next();
        dataSetsCreated.put(sdk, created.getId());
        return created.getExternalId();
    }

    /** {@code count} float series in the dataset, each with one point a second over the span. */
    private List<String> sources(DatahubClient sdk, String dataSet, String kind, int count, Instant from, Instant to) {
        long dataSetId = dataSetsCreated.get(sdk);
        List<Timeseries> series = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = runId + "_" + kind + "_" + i;
            Timeseries s = new Timeseries();
            s.setExternalId(id);
            s.setName(id);
            s.setValueType("float");
            s.setUnit("celsius");
            s.setDataSetId(dataSetId);
            series.add(s);
            ids.add(id);
        }
        sdk.timeseries().create(series);
        seriesCreated.computeIfAbsent(sdk, c -> new ArrayList<>()).addAll(ids);
        ids.forEach(id -> seriesCreated.get(sdk).add(id + "_out"));
        Map<String, List<Datapoint>> points = new LinkedHashMap<>();
        for (String id : ids) {
            List<Datapoint> p = new ArrayList<>();
            for (Instant t = from; t.isBefore(to); t = t.plusSeconds(1)) {
                p.add(Datapoint.of(t, 20 + (t.getEpochSecond() % 60) / 10.0));
            }
            points.put(id, p);
        }
        var result = sdk.timeseries().ingest(points);
        assertThat(result.isComplete()).as("ingest: %s", result).isTrue();
        return ids;
    }

    private static int points(DatahubClient sdk, String series, Instant from, Instant to) {
        RetrieveFilter filter = new RetrieveFilter();
        filter.setExternalId(series);
        filter.setStart(from.atZone(ZoneOffset.UTC));
        filter.setEnd(to.atZone(ZoneOffset.UTC));
        filter.setLimit(100_000);
        DataRetriever<RetrieveFilter> request = new DataRetriever<>();
        request.getItems().add(filter);
        int count = 0;
        for (DatapointsCollection c : sdk.timeseries().retrieve(request).getItems()) {
            count += c.getDatapoints() == null ? 0 : c.getDatapoints().size();
        }
        return count;
    }

    private void registerRunClient(Client as, String clientId, String secret) {
        Response put = FlowStack.put(FlowStack.apiUrl() + "/tenant/settings/flow/clients/" + RUN_CLIENT, as,
                FlowStack.json(FlowStack.map("clientId", clientId, "clientSecret", secret)));
        assertThat(put.status()).as("registering %s as a flow run client: %s", clientId, put.body()).isEqualTo(204);
    }

    private static void await(BooleanSupplier condition, Duration timeout, Supplier<String> onTimeout) {
        await(condition, timeout, Duration.ofSeconds(2), onTimeout);
    }

    private static void await(BooleanSupplier condition, Duration timeout, Duration every, Supplier<String> onTimeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out after " + timeout + ": " + onTimeout.get());
            }
            FlowStack.sleep(every.toMillis());
        }
    }
}
