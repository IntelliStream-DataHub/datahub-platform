// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.responses.DataRetriever;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.DatapointsCollection;
import ai.intellistream.datahub.api.responses.GraphDataWrapper;
import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.label.LabelForm;
import ai.intellistream.datahub.models.Asset;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.DataSetRetreiver;
import ai.intellistream.datahub.models.DeleteDatapoint;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.FetchNearestResourcesForm;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.NodeModelSubtypes;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.RelForm;
import ai.intellistream.datahub.models.RelatedResourcesForm;
import ai.intellistream.datahub.models.ResourceRetreiver;
import ai.intellistream.datahub.models.SearchBody;
import ai.intellistream.datahub.models.TimeseriesRetreiver;
import ai.intellistream.datahub.models.UUIDAndExternalIdCollection;
import ai.intellistream.datahub.models.UpdateEventForm;
import ai.intellistream.datahub.models.UpdateRelForm;
import ai.intellistream.datahub.models.UpdateResourceForm;
import ai.intellistream.datahub.models.datafilters.DataSetFilter;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import ai.intellistream.datahub.models.datafilters.TimeseriesFilter;
import ai.intellistream.datahub.models.events.EventFilter;
import ai.intellistream.datahub.models.events.EventRetreiver;
import ai.intellistream.datahub.models.files.FileUpdate;
import ai.intellistream.datahub.models.forms.DataSetForm;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.datahub.models.forms.UpdatePolicyForm;
import ai.intellistream.datahub.models.policy.NamingCheckForm;
import ai.intellistream.datahub.models.tenant.TenantLlmSettingsForm;
import ai.intellistream.datahub.resource.RelTypeForm;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.services.FileUploadRequest;
import ai.intellistream.datahub.subscription.Subscription;
import ai.intellistream.datahub.subscription.SubscriptionRetriever;
import ai.intellistream.datahub.timeseries.Timeseries;
import ai.intellistream.datahub.timeseries.UpdateTimeseries;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.TypeFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every call in the SDK, driven against a stub server, checked against what the endpoint binds.
 *
 * <p>Two questions per call, both of which have been answered wrongly before. Does it go to the
 * verb and path the controller maps? And does the body it sends deserialize into the controller's
 * {@code @RequestBody} type under {@code StrictRequestBodyConfig}, which rejects a request naming a
 * field the api does not have?
 *
 * <p>The SDK's other tests assert on responses, so a call that could only ever have produced a
 * {@code 400} still passed: {@code units().byIds} posted whole {@code UnitModel}s into a body that
 * binds {@code IdCollection}, and {@code events().byIds} posted a {@code Long} id where an event id
 * is a UUID. This is the table that makes those visible without a running backend, and it is meant
 * to grow a row per call: an endpoint reachable from {@link DatahubClient} belongs in {@link #ALL}.
 */
class SdkWireContractTest {

    /** One SDK call, with the verb, path and binding type the api side of it declares. */
    private record Contract(String label, String method, String path, JavaType binds,
                            java.util.function.Consumer<DatahubClient> call, String response) {

        Contract(String label, String method, String path, JavaType binds,
                 java.util.function.Consumer<DatahubClient> call) {
            this(label, method, path, binds, call, WRAPPER_RESPONSE);
        }
    }

    /**
     * What the stub answers with. Assertions are on the request, so this only has to parse as the
     * call's declared response type; most are {@code DataWrapper}s, and the handful that are
     * records with primitive components need their own because an absent component is not a
     * default, it is a null the record constructor rejects.
     */
    private static final String WRAPPER_RESPONSE = "{\"items\":[]}";

    private static final String IMPORT_RESULT_RESPONSE = """
            {"nodesCreated":2,"relationsCreated":1,"nodesSkippedExisting":0,
             "nodesSkippedTimeseries":[],"relationsSkipped":0,"dataSetReferencesDropped":0,
             "segments":1,"warnings":[]}""";

    private static final String LLM_SETTINGS_RESPONSE = """
            {"provider":"anthropic","model":"claude-opus-5","baseUrl":null,"reasoningEffort":null,
             "effort":"medium","turnTimeout":null,"maxOutputTokens":null,"maxIterations":null,
             "instructions":null,"apiKeySet":true,"configured":true}""";

    private static final String PERMISSIONS_RESPONSE = "{\"llm\":{\"read\":true,\"write\":false}}";

    private static final String VALUE_TYPE_RESPONSE = """
            {"unitExternalId":"temperature_deg_c","recommendedValueType":"FLOAT32",
             "reason":"a temperature reading","recognized":true}""";

    /** Mirrors StrictRequestBodyConfig: a request naming an unknown field is a 400, not a no-op. */
    private static final JsonMapper STRICT = JsonMapper.builder()
            .addModule(new NodeModelSubtypes())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final TypeFactory TF = STRICT.getTypeFactory();

    private static JavaType wrapper(Class<?> item) {
        return TF.constructParametricType(DataWrapper.class, item);
    }

    private static JavaType retriever(Class<?> item) {
        return TF.constructParametricType(DataRetriever.class, item);
    }

    private static JavaType searchBody(Class<?> filter) {
        return TF.constructParametricType(SearchBody.class, filter);
    }

    private static JavaType graph(Class<?> node, Class<?> relation) {
        return TF.constructParametricType(GraphDataWrapper.class, node, relation);
    }

    private static final JavaType IDS = wrapper(IdCollection.class);
    private static final JavaType EVENT_IDS = wrapper(UUIDAndExternalIdCollection.class);

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> stubResponse = new AtomicReference<>(WRAPPER_RESPONSE);

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = stubResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (var os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private DatahubClient client() {
        return DatahubClient.create(DatahubConfig.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("test-token")
                .build());
    }

    // --- fixtures ---------------------------------------------------------------------------

    private static List<IdCollection> ids() {
        return List.of(IdCollection.createFromExternalId("x"));
    }

    private static Asset asset() {
        Asset a = new Asset();
        a.setExternalId("pump_1");
        a.setName("Pump 1");
        return a;
    }

    private static final List<Contract> ALL = List.of(
            // --- resources ---------------------------------------------------------------
            new Contract("resources.list", "GET", "/resources", null,
                    c -> c.resources().list(10)),
            new Contract("resources.getById", "GET", "/resources/1", null,
                    c -> c.resources().getById(1)),
            new Contract("resources.byIds", "POST", "/resources/byids", IDS,
                    c -> c.resources().byIds(ids())),
            new Contract("resources.filter", "POST", "/resources/filter",
                    TF.constructType(ResourceRetreiver.class),
                    c -> c.resources().filter(new ResourceFilter())),
            new Contract("resources.search", "POST", "/resources/search",
                    searchBody(ResourceFilter.class),
                    c -> c.resources().search(new SearchBody<>())),
            new Contract("resources.create", "POST", "/resources/create",
                    graph(NodeModel.class, RelForm.class),
                    c -> c.resources().create(List.of(asset()), List.of(new RelForm()))),
            new Contract("resources.update", "POST", "/resources/update",
                    graph(UpdateResourceForm.class, UpdateRelForm.class),
                    c -> c.resources().update(List.of(new UpdateResourceForm()))),
            new Contract("resources.delete", "DELETE", "/resources/delete", IDS,
                    c -> c.resources().delete(ids())),
            new Contract("resources.fetchRelated", "POST", "/resources/fetch-related",
                    TF.constructType(RelatedResourcesForm.class),
                    c -> c.resources().fetchRelated(new RelatedResourcesForm())),
            new Contract("resources.fetchNearest", "POST", "/resources/fetch-nearest",
                    TF.constructType(FetchNearestResourcesForm.class),
                    c -> c.resources().fetchNearest(new FetchNearestResourcesForm())),
            new Contract("resources.export", "GET", "/resources/export/7", null,
                    c -> c.resources().export(7)),
            new Contract("resources.importGraph", "POST", "/resources/import", null,
                    c -> c.resources().importGraph(new byte[] {1, 2, 3}), IMPORT_RESULT_RESPONSE),

            // --- assets ------------------------------------------------------------------
            new Contract("assets.create", "POST", "/assets/create", wrapper(Asset.class),
                    c -> c.assets().create(List.of(asset()))),
            new Contract("assets.getById", "GET", "/assets/1", null,
                    c -> c.assets().getById(1)),
            new Contract("assets.byIds", "POST", "/assets/byids", IDS,
                    c -> c.assets().byIds(ids())),
            new Contract("assets.list", "GET", "/assets", null,
                    c -> c.assets().list(10)),
            new Contract("assets.filter", "POST", "/assets/filter",
                    TF.constructType(ResourceRetreiver.class),
                    c -> c.assets().filter(new ResourceFilter())),
            new Contract("assets.search", "POST", "/assets/search", searchBody(ResourceFilter.class),
                    c -> c.assets().search(new SearchBody<>())),
            new Contract("assets.update", "POST", "/assets/update",
                    graph(UpdateResourceForm.class, UpdateRelForm.class),
                    c -> c.assets().update(List.of(new UpdateResourceForm()))),
            new Contract("assets.delete", "DELETE", "/assets/delete", IDS,
                    c -> c.assets().delete(ids())),

            // --- functions ---------------------------------------------------------------
            new Contract("functions.create", "POST", "/functions/create", wrapper(Function.class),
                    c -> c.functions().create(List.of(new Function()))),
            new Contract("functions.list", "GET", "/functions", null,
                    c -> c.functions().list(10)),
            new Contract("functions.getById", "GET", "/functions/1", null,
                    c -> c.functions().getById(1)),
            new Contract("functions.update", "POST", "/functions/update",
                    graph(UpdateResourceForm.class, UpdateRelForm.class),
                    c -> c.functions().update(List.of(new UpdateResourceForm()))),
            new Contract("functions.delete", "DELETE", "/functions/delete", IDS,
                    c -> c.functions().delete(ids())),

            // --- edges -------------------------------------------------------------------
            new Contract("edges.findById", "GET", "/edges/1", null,
                    c -> c.edges().findById(1)),
            new Contract("edges.byIds", "POST", "/edges/byids", IDS,
                    c -> c.edges().byIds(ids())),
            new Contract("edges.create", "POST", "/edges/create", wrapper(RelForm.class),
                    c -> c.edges().create(List.of(new RelForm()))),
            new Contract("edges.types", "GET", "/edges/types", null,
                    c -> c.edges().types()),
            new Contract("edges.createTypes", "POST", "/edges/types/create", wrapper(RelTypeForm.class),
                    c -> c.edges().createTypes(List.of(new RelTypeForm()))),
            new Contract("edges.delete", "DELETE", "/edges/delete", IDS,
                    c -> c.edges().delete(ids())),

            // --- timeseries --------------------------------------------------------------
            new Contract("timeseries.list", "GET", "/timeseries", null,
                    c -> c.timeseries().list(10)),
            new Contract("timeseries.listByDataSet", "GET", "/timeseries", null,
                    c -> c.timeseries().list(10, "plant_a")),
            new Contract("timeseries.getById", "GET", "/timeseries/1", null,
                    c -> c.timeseries().getById(1)),
            new Contract("timeseries.recommendValueType", "GET",
                    "/timeseries/recommend-value-type/temperature_deg_c", null,
                    c -> c.timeseries().recommendValueType("temperature_deg_c"), VALUE_TYPE_RESPONSE),
            new Contract("timeseries.byIds", "POST", "/timeseries/byids", IDS,
                    c -> c.timeseries().byIds(ids())),
            new Contract("timeseries.create", "POST", "/timeseries/create", wrapper(Timeseries.class),
                    c -> c.timeseries().create(new Timeseries())),
            new Contract("timeseries.update", "POST", "/timeseries/update", wrapper(UpdateTimeseries.class),
                    c -> c.timeseries().update(List.of(new UpdateTimeseries()))),
            new Contract("timeseries.filter", "POST", "/timeseries/filter",
                    TF.constructType(TimeseriesRetreiver.class),
                    c -> c.timeseries().filter(new TimeseriesFilter())),
            new Contract("timeseries.search", "POST", "/timeseries/search",
                    searchBody(TimeseriesFilter.class),
                    c -> c.timeseries().search("pressure")),
            new Contract("timeseries.delete", "POST", "/timeseries/delete", IDS,
                    c -> c.timeseries().delete(ids())),
            new Contract("timeseries.insertDatapoints", "POST", "/timeseries/data",
                    wrapper(DatapointsCollection.class),
                    c -> c.timeseries().insertDatapoints(List.of(new DatapointsCollection()))),
            new Contract("timeseries.retrieve", "POST", "/timeseries/data/list",
                    retriever(RetrieveFilter.class),
                    c -> c.timeseries().retrieve(new DataRetriever<>())),
            new Contract("timeseries.latest", "POST", "/timeseries/data/latest", IDS,
                    c -> c.timeseries().latest(ids())),
            new Contract("timeseries.deleteDatapoints", "POST", "/timeseries/data/delete",
                    retriever(DeleteDatapoint.class),
                    c -> c.timeseries().deleteDatapoints("x", Instant.EPOCH, Instant.EPOCH)),

            // --- datasets ----------------------------------------------------------------
            new Contract("datasets.getById", "GET", "/datasets/1", null,
                    c -> c.datasets().getById(1)),
            new Contract("datasets.list", "GET", "/datasets", null,
                    c -> c.datasets().list(10)),
            new Contract("datasets.policies", "GET", "/datasets/policies", null,
                    c -> c.datasets().policies()),
            new Contract("datasets.byIds", "POST", "/datasets/byids", IDS,
                    c -> c.datasets().byIds(ids())),
            new Contract("datasets.filter", "POST", "/datasets/filter",
                    TF.constructType(DataSetRetreiver.class),
                    c -> c.datasets().filter(new DataSetFilter())),
            new Contract("datasets.search", "POST", "/datasets/search", searchBody(DataSetFilter.class),
                    c -> c.datasets().search(new SearchBody<>())),
            new Contract("datasets.create", "POST", "/datasets/create", wrapper(DataSetModel.class),
                    c -> c.datasets().create(List.of(new DataSetModel()))),
            new Contract("datasets.update", "POST", "/datasets/update", wrapper(DataSetForm.class),
                    c -> c.datasets().update(List.of(new DataSetForm()))),
            new Contract("datasets.delete", "POST", "/datasets/delete", IDS,
                    c -> c.datasets().delete(ids())),

            // --- events ------------------------------------------------------------------
            new Contract("events.list", "GET", "/events", null,
                    c -> c.events().list(10)),
            new Contract("events.getById", "GET", "/events/" + UUID.nameUUIDFromBytes(new byte[0]), null,
                    c -> c.events().getById(UUID.nameUUIDFromBytes(new byte[0]).toString())),
            new Contract("events.byIds", "POST", "/events/byids", EVENT_IDS,
                    c -> c.events().byIds(List.of(UUIDAndExternalIdCollection.createFromExternalId("e")))),
            new Contract("events.filter", "POST", "/events/filter",
                    TF.constructType(EventRetreiver.class),
                    c -> c.events().filter(new EventFilter())),
            new Contract("events.search", "POST", "/events/search", searchBody(EventFilter.class),
                    c -> c.events().search(new SearchBody<>())),
            new Contract("events.create", "POST", "/events/create", wrapper(EventModel.class),
                    c -> c.events().create(List.of(new EventModel()))),
            new Contract("events.update", "POST", "/events/update", wrapper(UpdateEventForm.class),
                    c -> c.events().update(List.of(new UpdateEventForm()))),
            new Contract("events.delete", "POST", "/events/delete", EVENT_IDS,
                    c -> c.events().delete(List.of(UUIDAndExternalIdCollection.createFromExternalId("e")))),
            new Contract("events.count", "GET", "/events/count", null,
                    c -> c.events().count()),
            new Contract("events.listTypes", "GET", "/events/list/types", null,
                    c -> c.events().listTypes(10)),
            new Contract("events.listSubTypes", "GET", "/events/list/sub-types", null,
                    c -> c.events().listSubTypes(10)),
            new Contract("events.listStatuses", "GET", "/events/list/statuses", null,
                    c -> c.events().listStatuses(10)),
            new Contract("events.listSources", "GET", "/events/list/sources", null,
                    c -> c.events().listSources(10)),
            new Contract("events.searchTypes", "GET", "/events/search/type", null,
                    c -> c.events().searchTypes("a", 10)),
            new Contract("events.searchSubTypes", "GET", "/events/search/sub-type", null,
                    c -> c.events().searchSubTypes("a", 10)),
            new Contract("events.searchStatuses", "GET", "/events/search/status", null,
                    c -> c.events().searchStatuses("a", 10)),
            new Contract("events.searchSources", "GET", "/events/search/source", null,
                    c -> c.events().searchSources("a", 10)),

            // --- labels ------------------------------------------------------------------
            new Contract("labels.getById", "GET", "/labels/1", null,
                    c -> c.labels().getById(1)),
            new Contract("labels.list", "GET", "/labels", null,
                    c -> c.labels().list()),
            new Contract("labels.create", "POST", "/labels/create", wrapper(LabelForm.class),
                    c -> c.labels().create(List.of(new LabelForm()))),
            new Contract("labels.update", "POST", "/labels/update", wrapper(LabelForm.class),
                    c -> c.labels().update(List.of(new LabelForm()))),
            new Contract("labels.delete", "DELETE", "/labels/delete", IDS,
                    c -> c.labels().delete(ids())),

            // --- policies ----------------------------------------------------------------
            new Contract("policies.list", "GET", "/policies", null,
                    c -> c.policies().list(10)),
            new Contract("policies.listTypes", "GET", "/policies/types", null,
                    c -> c.policies().listTypes()),
            new Contract("policies.getById", "GET", "/policies/1", null,
                    c -> c.policies().getById(1)),
            new Contract("policies.create", "POST", "/policies/create", wrapper(Policy.class),
                    c -> c.policies().create(List.of(new Policy()))),
            new Contract("policies.update", "POST", "/policies/update", wrapper(UpdatePolicyForm.class),
                    c -> c.policies().update(List.of(new UpdatePolicyForm()))),
            new Contract("policies.checkNaming", "POST", "/policies/naming/check",
                    TF.constructType(NamingCheckForm.class),
                    c -> c.policies().checkNaming(new NamingCheckForm())),
            new Contract("policies.delete", "DELETE", "/policies/delete", IDS,
                    c -> c.policies().delete(ids())),

            // --- governance --------------------------------------------------------------
            new Contract("governance.listTemplates", "GET", "/governance/templates", null,
                    c -> c.governance().listTemplates()),
            new Contract("governance.getTemplateById", "GET", "/governance/templates/3", null,
                    c -> c.governance().getTemplateById(3)),

            // --- tenant ------------------------------------------------------------------
            new Contract("tenant.features", "GET", "/tenant/features", null,
                    c -> c.tenant().features()),
            new Contract("tenant.settingsPermissions", "GET", "/tenant/settings/permissions", null,
                    c -> c.tenant().settingsPermissions(), PERMISSIONS_RESPONSE),
            new Contract("tenant.llmSettings", "GET", "/tenant/settings/llm", null,
                    c -> c.tenant().llmSettings(), LLM_SETTINGS_RESPONSE),
            new Contract("tenant.updateLlmSettings", "PUT", "/tenant/settings/llm",
                    TF.constructType(TenantLlmSettingsForm.class),
                    c -> c.tenant().updateLlmSettings(new TenantLlmSettingsForm(
                            "anthropic", "claude-opus-5", null, null, null, null, null, null, null, null)),
                    LLM_SETTINGS_RESPONSE),

            // --- units -------------------------------------------------------------------
            new Contract("units.list", "GET", "/units", null,
                    c -> c.units().list()),
            new Contract("units.getByExternalId", "GET", "/units/temperature_deg_c", null,
                    c -> c.units().getByExternalId("temperature_deg_c")),
            new Contract("units.byIds", "POST", "/units/byids", IDS,
                    c -> c.units().byIds(ids())),

            // --- files -------------------------------------------------------------------
            new Contract("files.list", "GET", "/files/list", null,
                    c -> c.files().list()),
            new Contract("files.listPath", "GET", "/files/list/reports", null,
                    c -> c.files().list("/reports")),
            new Contract("files.getById", "GET", "/files", null,
                    c -> c.files().getById(4)),
            new Contract("files.getByExternalId", "GET", "/files", null,
                    c -> c.files().getByExternalId("report_2026_q2")),
            new Contract("files.search", "GET", "/files/search", null,
                    c -> c.files().search("calibration", 25)),
            new Contract("files.trash", "GET", "/files/trash", null,
                    c -> c.files().trash()),
            new Contract("files.download", "GET", "/files/download/4", null,
                    c -> c.files().download("4")),
            new Contract("files.upload", "PUT", "/files", null,
                    c -> c.files().upload(FileUploadRequest.builder()
                            .path("/reports/q2.txt").content("x".getBytes(StandardCharsets.UTF_8)).build())),
            new Contract("files.update", "POST", "/files/update",
                    TF.constructType(FileUpdate.class),
                    c -> c.files().update(new FileUpdate())),
            new Contract("files.restore", "POST", "/files/restore", IDS,
                    c -> c.files().restore(ids())),
            new Contract("files.delete", "POST", "/files/delete", IDS,
                    c -> c.files().delete(ids())),

            // --- subscriptions -----------------------------------------------------------
            new Contract("subscriptions.list", "GET", "/subscriptions", null,
                    c -> c.subscriptions().list(10)),
            new Contract("subscriptions.create", "POST", "/subscriptions/create", wrapper(Subscription.class),
                    c -> c.subscriptions().create(List.of(new Subscription()))),
            new Contract("subscriptions.filter", "POST", "/subscriptions/filter",
                    TF.constructType(SubscriptionRetriever.class),
                    c -> c.subscriptions().filter(new SubscriptionRetriever())),
            new Contract("subscriptions.delete", "POST", "/subscriptions/delete", IDS,
                    c -> c.subscriptions().delete(ids())));

    @Test
    @DisplayName("every call uses the verb and path its controller maps")
    void verbsAndPathsMatchTheControllers() {
        List<String> wrong = new ArrayList<>();
        for (Contract contract : ALL) {
            reset(contract);
            contract.call().accept(client());
            String actual = method.get() + " " + path.get();
            String expected = contract.method() + " " + contract.path();
            if (!expected.equals(actual)) {
                wrong.add(contract.label() + ": expected " + expected + ", sent " + actual);
            }
        }
        assertEquals(List.of(), wrong, String.join("\n", wrong));
    }

    @Test
    @DisplayName("every request body binds into the type its endpoint declares")
    void bodiesBindIntoWhatTheEndpointsDeclare() {
        List<String> wrong = new ArrayList<>();
        for (Contract contract : ALL) {
            if (contract.binds() == null) {
                continue;   // GET, or a raw byte body the api reads as a stream
            }
            reset(contract);
            contract.call().accept(client());
            try {
                STRICT.readValue(body.get(), contract.binds());
            } catch (RuntimeException e) {
                wrong.add(contract.label() + " -> " + contract.binds() + "\n    sent " + body.get()
                        + "\n    " + e.getMessage());
            }
        }
        assertEquals(List.of(), wrong, String.join("\n", wrong));
    }

    private void reset(Contract contract) {
        method.set(null);
        path.set(null);
        body.set("");
        stubResponse.set(contract.response());
    }
}
