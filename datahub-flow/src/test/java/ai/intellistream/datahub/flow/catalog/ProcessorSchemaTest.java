// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.catalog;

import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.processors.EventsSink;
import ai.intellistream.datahub.flow.processors.TimeseriesSink;
import ai.intellistream.datahub.flow.processors.TimeseriesSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The catalog entry of a processor: its properties as JSON Schema, as the console's forms read it. */
class ProcessorSchemaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode entry(Processor processor) {
        return JSON.valueToTree(ProcessorSchema.of(processor.describe()));
    }

    @Test
    void anEntryNamesTheProcessorItsVersionAndItsRelationships() {
        JsonNode sink = entry(new TimeseriesSink());

        assertThat(sink.get("name").stringValue()).isEqualTo("write-timeseries");
        assertThat(sink.get("displayName").stringValue()).isEqualTo("Write timeseries");
        assertThat(sink.get("category").stringValue()).isEqualTo("read-write");
        assertThat(sink.get("version").stringValue()).isEqualTo("1.0.0");
        assertThat(sink.get("relationships").findValuesAsString("name")).containsExactly("success", "failure");
        assertThat(sink.get("relationships").get(0).get("whenUnconnected").stringValue()).isEqualTo("terminate");
        assertThat(sink.get("relationships").get(1).get("whenUnconnected").stringValue()).isEqualTo("fail-run");
    }

    @Test
    void propertiesAreAJsonSchemaObject() {
        JsonNode schema = entry(new TimeseriesSource()).get("properties");

        assertThat(schema.get("$schema").stringValue()).isEqualTo(ProcessorSchema.DIALECT);
        assertThat(schema.get("type").stringValue()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").booleanValue()).isFalse();
        // A required property with a default is not required of the definition.
        assertThat(JSON.convertValue(schema.get("required"), List.class)).containsExactly("timeseries");

        JsonNode p = schema.get("properties");
        assertThat(p.get("timeseries").get("type").stringValue()).isEqualTo("string");
        assertThat(p.get("lookback").get("format").stringValue()).isEqualTo("duration");
        assertThat(p.get("lookback").get("default").stringValue()).isEqualTo("PT0S");
        assertThat(p.get("maxPointsPerItem").get("default").intValue()).isEqualTo(100_000);
        assertThat(p.get("onMissing").get("oneOf").findValuesAsString("const")).containsExactly("fail", "skip");
        assertThat(p.get("onMissing").get("default").stringValue()).isEqualTo("fail");
    }

    @Test
    void aPropertyThatTakesReferencesSaysSo() {
        JsonNode p = entry(new TimeseriesSource()).get("properties").get("properties");

        // An instant may be one reference instead: ${run.window.start}.
        JsonNode from = p.get("from");
        assertThat(from.get("x-references").booleanValue()).isTrue();
        assertThat(from.get("anyOf").get(0).get("format").stringValue()).isEqualTo("date-time");
        assertThat(from.get("anyOf").get(1).get("pattern").stringValue()).isEqualTo(ProcessorSchema.REFERENCE);
        assertThat(from.get("default").stringValue()).isEqualTo("${run.window.start}");

        // A string may hold references anywhere, so it stays a string.
        JsonNode target = entry(new TimeseriesSink()).get("properties").get("properties").get("target");
        assertThat(target.get("type").stringValue()).isEqualTo("string");
        assertThat(target.get("x-references").booleanValue()).isTrue();
    }

    @Test
    void theConsoleIsToldWhatToSearchForAndWhatToFoldAway() {
        JsonNode p = entry(new TimeseriesSource()).get("properties").get("properties");

        assertThat(p.get("timeseries").get("x-lookup").stringValue()).isEqualTo("timeseries");
        assertThat(p.get("timeseries").has("x-advanced")).isFalse();
        assertThat(p.get("from").has("x-lookup")).isFalse();
        // An optional property without a default is folded away by being marked advanced.
        JsonNode events = entry(new EventsSink()).get("properties").get("properties");
        assertThat(events.get("subType").get("x-advanced").booleanValue()).isTrue();
        // A required one with a default is not folded, though the default satisfies it.
        assertThat(events.get("type").get("x-required").booleanValue()).isTrue();
        assertThat(entry(new EventsSink()).get("properties").get("required").valueStream()
                .map(JsonNode::stringValue)).doesNotContain("type");
    }

    @Test
    void aBooleanCanBeTwoTabsWithWhatAppliesOnlyWhenItIsTrue() {
        JsonNode sink = entry(new TimeseriesSink()).get("properties").get("properties");

        assertThat(sink.get("create").get("x-tabs").valueStream().map(JsonNode::stringValue))
                .containsExactly("Existing series", "New series");
        assertThat(sink.get("unit").get("x-onlyWith").stringValue()).isEqualTo("create");
        assertThat(sink.get("dataSet").get("x-onlyWith").stringValue()).isEqualTo("create");
        // The target applies either way.
        assertThat(sink.get("target").has("x-onlyWith")).isFalse();
        assertThat(sink.get("target").get("x-required").booleanValue()).isTrue();
    }

    @Test
    void dynamicPropertiesAreAdditionalProperties() {
        JsonNode events = entry(new EventsSink()).get("properties").get("additionalProperties");
        assertThat(events.get("type").stringValue()).isEqualTo("string");
        assertThat(events.get("x-references").booleanValue()).isTrue();
        assertThat(events.get("x-keyDescription").stringValue()).contains("metadata key");
    }

    @Test
    void anEnumListsItsValuesWithTheirDescriptions() {
        JsonNode onMissing = entry(new TimeseriesSource()).get("properties").get("properties").get("onMissing");

        assertThat(onMissing.get("type").stringValue()).isEqualTo("string");
        assertThat(onMissing.get("oneOf").findValuesAsString("const")).containsExactly("fail", "skip");
        assertThat(onMissing.get("oneOf").get(0).get("description").stringValue()).isEqualTo("Fail the run.");
    }
}
