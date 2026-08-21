// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.wire;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.DatapointsCollection;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.models.TimeseriesRetreiver;
import ai.intellistream.datahub.models.UUIDAndExternalIdCollection;
import ai.intellistream.datahub.models.events.EventRetreiver;
import ai.intellistream.datahub.models.files.FileUpdate;
import ai.intellistream.datahub.models.forms.AnalysisForm;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.datahub.models.policy.NamingCheckForm;
import ai.intellistream.datahub.models.SearchBody;
import ai.intellistream.datahub.models.datafilters.DataSetFilter;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pins the request bodies the console builds <em>in the browser</em> to the api-model DTOs the api
 * binds them to.
 *
 * <p>These payloads are the console's one unchecked path to datahub-api. Calls that go through the
 * console's own controllers reach the api via the typed Feign client, so renaming an api-model field
 * breaks the console build; page JS naming that field in a string of JSON breaks nothing, and the
 * api answered {@code 200} while dropping it. That is how {@code externalIdPrefix} kept filtering
 * nothing for two releases after #308 retired it, and how a {@code dataSetId} of the wrong shape
 * listed every timeseries instead of one dataset's.
 *
 * <p>The counterpart to the {@code *WireContractTest} family in {@code datahub-api-model}: those pin
 * what a DTO <em>serializes to</em>, this pins what a caller still <em>sends</em>. Both stayed green
 * through #308 — the DTO was fine, the page was the thing that had gone stale.
 *
 * <p>Each payload is read with {@code FAIL_ON_UNKNOWN_PROPERTIES} enabled — the same feature
 * {@code StrictRequestBodyConfig} turns on for {@code @RequestBody} — so this test fails exactly
 * where the api would answer 400. A payload here could still drift from the page it claims to
 * mirror, so the second check reads that page and asserts every field name still appears in it.
 * Field names under {@code metadata} are skipped: that map's keys are user data, not contract.
 *
 * <p><b>What this does not do:</b> it does not prove the page sends this exact JSON — a payload and
 * its page can be edited together into something wrong. It catches the failure that actually
 * happened twice: api-model moving underneath a payload nobody re-read.
 */
class BrowserPayloadContractTest {

    /** Field names whose children are free-form user data rather than part of the contract. */
    private static final Set<String> OPAQUE = Set.of("metadata");

    /** Where the pages live, relative to the module dir Gradle runs tests from. */
    private static final Path PAGES = Path.of("src/main/resources");

    private final JsonMapper strict = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * One browser-direct payload: the endpoint it goes to, the body as the page builds it, the DTO
     * the api binds it to, and the page that builds it (relative to {@link #PAGES}).
     */
    private record Payload(String endpoint, String json, TypeReference<?> type, String source) {
    }

    private static List<Payload> payloads() {
        return List.of(
                new Payload("POST /events/filter", """
                        {"filter":{"externalId":"work_order_*","type":"Alarm","subType":"threshold",
                        "status":"OPEN","source":"SAP","dataSetId":[{"id":"9223372036854775806"}],
                        "eventTime":{"min":"2026-08-01T00:00:00Z","max":"2026-08-02T00:00:00Z"},
                        "metadata":{"health":null}}}""",
                        new TypeReference<EventRetreiver>() {},
                        "templates/events/index.html"),

                new Payload("POST /events/filter (findings queue)", """
                        {"filter":{"type":"policy_finding","subType":"qualified_tag",
                        "dataSetId":[{"id":"9223372036854775806"}],
                        "relatedResources":[{"id":"9223372036854775806"}]},
                        "limit":200,"sort":{"property":["eventTime"],"order":"asc"},
                        "cursor":"1755000000000_1"}""",
                        new TypeReference<EventRetreiver>() {},
                        "static/js/policy/naming-policy.js"),

                new Payload("POST /timeseries/filter", """
                        {"limit":10000,"filter":{"dataSetId":[{"id":"9223372036854775806"}]}}""",
                        new TypeReference<TimeseriesRetreiver>() {},
                        "templates/datasets/timeseries.html"),

                new Payload("POST /events/create", """
                        {"items":[{"externalId":"tutorial_alarm_1","eventTime":1755000000000,
                        "type":"alarm","subType":"threshold","description":"d",
                        "relatedResources":[{"externalId":"res_1"}],"metadata":{"source":"tutorial"}}]}""",
                        new TypeReference<DataWrapper<EventModel>>() {},
                        "static/js/tutorials/datasets.js"),

                new Payload("POST /events/create (resolve a finding)", """
                        {"items":[{"externalId":"finding_1","type":"policy_finding",
                        "subType":"qualified_tag","status":"RESOLVED","eventTime":"2026-08-13T10:00:00Z",
                        "relatedResources":[{"id":"9223372036854775806"}],
                        "dataSetId":"9223372036854775806"}]}""",
                        new TypeReference<DataWrapper<EventModel>>() {},
                        "static/js/policy/naming-policy.js"),

                new Payload("POST /events/delete", """
                        {"items":[{"externalId":"tutorial_alarm_1"}]}""",
                        new TypeReference<DataWrapper<UUIDAndExternalIdCollection>>() {},
                        "static/js/tutorials/datasets.js"),

                new Payload("POST /policies/naming/check", """
                        {"externalIds":["21-PT-1234"],"dataSetId":"9223372036854775806"}""",
                        new TypeReference<NamingCheckForm>() {},
                        "static/js/policy/naming-policy.js"),

                new Payload("POST /timeseries/data/list", """
                        {"items":[{"externalId":"21-PT-1234","start":"2026-08-01T00:00:00Z",
                        "end":"2026-08-02T00:00:00Z","aggregates":["avg","min","max"],
                        "granularity":"5 min","limit":100000}]}""",
                        new TypeReference<DataWrapper<RetrieveFilter>>() {},
                        "static/js/analyze.js"),

                new Payload("POST /timeseries/data", """
                        {"items":[{"externalId":"21-PT-1234",
                        "datapoints":[{"timestamp":"1755000000000","value":"1.5"}]}]}""",
                        new TypeReference<DataWrapper<DatapointsCollection>>() {},
                        "static/js/tutorials/datasets.js"),

                new Payload("POST /timeseries/byids", """
                        {"items":[{"id":"9223372036854775806"}]}""",
                        new TypeReference<DataWrapper<IdCollection>>() {},
                        "templates/timeseries/index.html"),

                new Payload("POST /files/delete", """
                        {"items":[{"externalId":"folder/file.csv"}]}""",
                        new TypeReference<DataWrapper<IdCollection>>() {},
                        "static/js/files-page.js"),

                new Payload("POST /files/update", """
                        {"externalId":"folder/file.csv","name":"file.csv","description":"d",
                        "source":"sap","metadata":{"owner":"ops"},
                        "relatedResources":["9223372036854775806"]}""",
                        new TypeReference<FileUpdate>() {},
                        "static/js/right-form-content/files/form.js"),

                new Payload("POST /files/update (set dataset)", """
                        {"externalId":"folder/file.csv","dataSetId":"9223372036854775806"}""",
                        new TypeReference<FileUpdate>() {},
                        "static/js/right-form-content/files/form.js"),

                // The create the dataset right-form posts. This is the payload that went stale:
                // it carried deactivated/writeProtected after #300 removed them and isRoot, which
                // a dataset never had, and every create 400'd once #324 started rejecting unknown
                // fields. Pinned here so the next removal breaks the build instead of the page.
                new Payload("POST /datasets/create", """
                        {"items":[{"name":"Pump readings","externalId":"pump_readings",
                        "description":"d","connectedDataSets":[],"policies":[]}]}""",
                        new TypeReference<DataWrapper<DataSetModel>>() {},
                        "static/js/right-form-content/datasets/form.js"),

                new Payload("POST /datasets/search", """
                        {"search":{"query":"pump"}}""",
                        new TypeReference<SearchBody<DataSetFilter>>() {},
                        "templates/datasets/index.html"),

                // Not datahub-api: the Analyze tab posts this straight to datahub-analysis.
                new Payload("POST /analysis", """
                        {"focusExternalId":"21-PT-1234","start":"2026-08-01T00:00:00Z",
                        "end":"2026-08-02T00:00:00Z","limit":10}""",
                        new TypeReference<AnalysisForm>() {},
                        "static/js/analyze.js"));
    }

    @TestFactory
    Stream<DynamicTest> everyBrowserPayloadStillBinds() {
        return payloads().stream().map(p -> DynamicTest.dynamicTest(
                p.endpoint() + " <- " + p.source(),
                () -> assertThatCode(() -> strict.readValue(p.json(), p.type()))
                        .as("%s builds this body for %s; it must bind to %s, so a rename in "
                                        + "api-model shows up here rather than as a 400 in the browser",
                                p.source(), p.endpoint(), p.type().getType())
                        .doesNotThrowAnyException()));
    }

    @TestFactory
    Stream<DynamicTest> everyFieldStillAppearsInThePageThatBuildsIt() {
        return payloads().stream().map(p -> DynamicTest.dynamicTest(
                p.endpoint() + " mirrors " + p.source(),
                () -> {
                    String page = Files.readString(PAGES.resolve(p.source()));
                    assertThat(fieldNames(strict.readTree(p.json())))
                            .allSatisfy(field -> assertThat(page)
                                    .as("the %s payload here claims to mirror %s, which no longer "
                                            + "mentions '%s'", p.endpoint(), p.source(), field)
                                    .contains(field));
                }));
    }

    /** Every field name in the payload, skipping the children of free-form maps. */
    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        collect(node, names);
        return names;
    }

    private static void collect(JsonNode node, Set<String> into) {
        if (node.isArray()) {
            node.forEach(child -> collect(child, into));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        for (Map.Entry<String, JsonNode> field : new ArrayList<>(node.properties())) {
            into.add(field.getKey());
            if (!OPAQUE.contains(field.getKey())) {
                collect(field.getValue(), into);
            }
        }
    }
}
