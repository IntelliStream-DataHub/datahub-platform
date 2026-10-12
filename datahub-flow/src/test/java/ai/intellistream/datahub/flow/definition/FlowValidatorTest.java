// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.definition;

import ai.intellistream.datahub.flow.fake.ExampleFlow;
import ai.intellistream.datahub.flow.fake.FakeProcessors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class FlowValidatorTest {

    private final FlowValidator validator = new FlowValidator(FakeProcessors.catalog(),
            FlowValidator.Limits.DEFAULTS);

    private List<ValidationProblem> problems(Consumer<ObjectNode> edit) {
        return problems(edit, null);
    }

    private List<ValidationProblem> problems(Consumer<ObjectNode> edit, Set<String> secrets) {
        ObjectNode tree = ExampleFlow.tree();
        edit.accept(tree);
        return validator.validate(FlowDefinitions.parse(tree.toString()), secrets);
    }

    private static ObjectNode processor(ObjectNode tree, int index) {
        return (ObjectNode) tree.get("processors").get(index);
    }

    private static ObjectNode properties(ObjectNode tree, int index) {
        return (ObjectNode) processor(tree, index).get("properties");
    }

    @Test
    void theExampleFlowIsValid() {
        assertThat(problems(tree -> { })).isEmpty();
    }

    @Test
    void reportsEverythingWrongAtOnceWithPointers() {
        List<ValidationProblem> problems = problems(tree -> {
            tree.put("schemaVersion", 2);
            tree.put("externalId", " bad id");
            properties(tree, 1).put("factor", "x");
        });
        assertThat(problems).extracting(ValidationProblem::pointer)
                .contains("#/schemaVersion", "#/externalId", "#/processors/1/properties/factor");
    }

    @Test
    void processorIdsAreUniqueAndTypesKnown() {
        List<ValidationProblem> problems = problems(tree -> {
            processor(tree, 1).put("id", "src");
            processor(tree, 2).put("type", "no.such.thing");
        });
        assertThat(problems).anySatisfy(p -> assertThat(p.message()).contains("\"src\" is used by another"));
        assertThat(problems).anySatisfy(p -> {
            assertThat(p.pointer()).isEqualTo("#/processors/2/type");
            assertThat(p.message()).contains("no.such.thing");
        });
    }

    @Test
    void connectionsMustJoinExistingProcessorsOnExistingRelationships() {
        List<ValidationProblem> problems = problems(tree -> {
            ArrayNode connections = (ArrayNode) tree.get("connections");
            connections.addObject().put("from", "src").put("relationship", "success").put("to", "nowhere");
            connections.addObject().put("from", "f").put("relationship", "sideways").put("to", "out");
        });
        assertThat(problems).extracting(ValidationProblem::message).anySatisfy(m ->
                assertThat(m).contains("No processor has the id \"nowhere\""));
        assertThat(problems).extracting(ValidationProblem::message).anySatisfy(m ->
                assertThat(m).contains("\"f\" has no relationship \"sideways\""));
    }

    @Test
    void everyRequiredRelationshipIsConnectedOrTerminated() {
        List<ValidationProblem> problems = problems(tree ->
                processor(tree, 3).set("terminate", ExampleFlow.JSON.createArrayNode()));
        assertThat(problems).singleElement().satisfies(p -> {
            assertThat(p.pointer()).isEqualTo("#/processors/3");
            assertThat(p.message()).contains("\"success\" of \"over\" must be connected");
        });
    }

    @Test
    void aSinksOutcomesNeedNeitherConnectionNorTerminate() {
        // out's success terminates by default and its failure fails the run; neither is wired.
        assertThat(problems(tree -> { })).isEmpty();
    }

    @Test
    void aRelationshipCannotBeBothConnectedAndTerminated() {
        List<ValidationProblem> problems = problems(tree ->
                ((ArrayNode) processor(tree, 3).get("terminate")).add("alarms"));
        assertThat(problems).singleElement().satisfies(p ->
                assertThat(p.message()).contains("both connected and terminated"));
    }

    @Test
    void terminateNamesExistingRelationships() {
        List<ValidationProblem> problems = problems(tree ->
                ((ArrayNode) processor(tree, 3).get("terminate")).add("nope"));
        assertThat(problems).singleElement().satisfies(p -> {
            assertThat(p.pointer()).isEqualTo("#/processors/3/terminate/1");
            assertThat(p.message()).contains("no relationship \"nope\"");
        });
    }

    @Test
    void cyclesAreRejected() {
        List<ValidationProblem> problems = problems(tree -> {
            ArrayNode processors = (ArrayNode) tree.get("processors");
            processors.addObject().put("id", "loop").put("type", "test.scale");
            ArrayNode connections = (ArrayNode) tree.get("connections");
            ((ArrayNode) processor(tree, 3).get("terminate")).removeAll();
            connections.addObject().put("from", "over").put("relationship", "success").put("to", "loop");
            connections.addObject().put("from", "loop").put("relationship", "success").put("to", "f");
        });
        assertThat(problems).anySatisfy(p -> assertThat(p.message()).contains("cycle"));
    }

    @Test
    void durableConnectionsAreNotAvailableYet() {
        List<ValidationProblem> problems = problems(tree ->
                ((ObjectNode) tree.get("connections").get(0)).put("durable", true));
        assertThat(problems).singleElement().satisfies(p ->
                assertThat(p.pointer()).isEqualTo("#/connections/0/durable"));
    }

    @Test
    void propertiesMustBeDeclaredOrDynamic() {
        List<ValidationProblem> problems = problems(tree -> {
            properties(tree, 1).put("factr", 2);
            // test.events.sink takes dynamic properties, so any name is fine there.
            properties(tree, 4).put("anything", "goes");
        });
        assertThat(problems).singleElement().satisfies(p -> {
            assertThat(p.pointer()).isEqualTo("#/processors/1/properties/factr");
            assertThat(p.message()).contains("has no property \"factr\"");
        });
    }

    @Test
    void requiredPropertiesMustBeSetUnlessTheyHaveADefault() {
        List<ValidationProblem> problems = problems(tree -> {
            properties(tree, 2).remove("target");
            properties(tree, 1).removeAll();     // factor and offset have defaults
        });
        assertThat(problems).singleElement().satisfies(p -> {
            assertThat(p.pointer()).isEqualTo("#/processors/2/properties/target");
            assertThat(p.message()).contains("Required");
        });
    }

    @Test
    void literalsMustFitTheirType() {
        List<ValidationProblem> problems = problems(tree -> {
            properties(tree, 0).put("points", "three");
            properties(tree, 0).put("from", "yesterday");
            properties(tree, 0).put("series", "ts_out_temp");
            properties(tree, 3).put("direction", "sideways");
        });
        assertThat(problems).extracting(ValidationProblem::message).containsExactlyInAnyOrder(
                "Must be a number.",
                "Must be an ISO-8601 instant such as 2026-10-01T00:00:00Z.",
                "Must be a list of strings.",
                "Must be one of above, below.");
    }

    @Test
    void referencesOnlyWhereAllowedAndOnlyToWhatExists() {
        List<ValidationProblem> problems = problems(tree -> {
            properties(tree, 0).put("points", "${parameters.limit}");          // points takes none
            properties(tree, 1).put("factor", "${parameters.nope}");
            properties(tree, 1).put("offset", "${run.duration}");
            properties(tree, 2).put("target", "${item.median}");
        });
        assertThat(problems).extracting(ValidationProblem::message).containsExactlyInAnyOrder(
                "This property does not take references.",
                "${parameters.nope}: the flow has no parameter \"nope\".",
                "${run.duration}: a run has run.window.end, run.window.start.",
                "${item.median}: an item's summary has item.count, item.last, item.lastTime, "
                        + "item.max, item.mean, item.min.");
    }

    @Test
    void textWithAReferenceInItIsOnlyForStrings() {
        List<ValidationProblem> problems = problems(tree ->
                properties(tree, 1).put("factor", "1${parameters.limit}"));
        assertThat(problems).singleElement().satisfies(p ->
                assertThat(p.message()).contains("only allowed for a string property"));
    }

    @Test
    void aMalformedReferenceIsReported() {
        List<ValidationProblem> problems = problems(tree ->
                properties(tree, 2).put("target", "${timeseries externalId}"));
        assertThat(problems).singleElement().satisfies(p ->
                assertThat(p.message()).contains("is not a valid reference"));
    }

    @Test
    void secretsAreOnlyReferencesAndOnlyInSecretProperties() {
        List<ValidationProblem> problems = problems(tree -> {
            ArrayNode processors = (ArrayNode) tree.get("processors");
            processors.addObject().put("id", "s1").put("type", "test.secret")
                    .putObject("properties").put("password", "hunter2");
            processors.addObject().put("id", "s2").put("type", "test.secret")
                    .putObject("properties").put("password", "${secrets.db}")
                    .put("note", "pw=${secrets.db}");
            processors.addObject().put("id", "s3").put("type", "test.secret")
                    .putObject("properties").put("password", "${secrets.missing}");
            for (String id : List.of("s1", "s2", "s3")) {
                ((ArrayNode) tree.get("connections")).addObject()
                        .put("from", "src").put("relationship", "success").put("to", id);
            }
            for (int i = 5; i < 8; i++) {
                processor(tree, i).putArray("terminate").add("success");
            }
        }, Set.of("db"));
        assertThat(problems).extracting(ValidationProblem::pointer, ValidationProblem::message)
                .containsExactlyInAnyOrder(
                        tuple("#/processors/5/properties/password",
                                "Must be a reference to a secret, ${secrets.<name>}: a secret's value "
                                        + "never goes in a definition."),
                        tuple("#/processors/6/properties/note",
                                "${secrets.db}: a secret can only be used in a property that is "
                                        + "declared secret or sensitive."),
                        tuple("#/processors/7/properties/password",
                                "${secrets.missing}: the organization has no flow secret called "
                                        + "\"missing\"."));
    }

    @Test
    void aProcessorChecksCombinationsOfItsOwnProperties() {
        List<ValidationProblem> problems = problems(tree -> {
            ((ArrayNode) tree.get("processors")).addObject().put("id", "r").put("type", "test.range")
                    .putArray("terminate").add("success");
            ((ArrayNode) tree.get("connections")).addObject()
                    .put("from", "src").put("relationship", "success").put("to", "r");
        });
        assertThat(problems).singleElement().satisfies(p -> {
            assertThat(p.pointer()).isEqualTo("#/processors/5/properties");
            assertThat(p.message()).isEqualTo("Set min, max or both.");
        });
    }

    @Test
    void dynamicRelationshipsMustBeWiredToo() {
        List<ValidationProblem> problems = problems(tree -> {
            ObjectNode route = ((ArrayNode) tree.get("processors")).addObject()
                    .put("id", "route").put("type", "test.route");
            route.putObject("properties").put("attribute", "unit").put("celsius", "C");
            route.putArray("terminate").add("unmatched");
            ((ArrayNode) tree.get("connections")).addObject()
                    .put("from", "src").put("relationship", "success").put("to", "route");
        });
        assertThat(problems).singleElement().satisfies(p ->
                assertThat(p.message()).contains("Relationship \"celsius\" of \"route\""));
    }

    @Test
    void aScheduleNeedsACronAWindowAndParameterDefaults() {
        List<ValidationProblem> problems = problems(tree -> {
            ObjectNode trigger = (ObjectNode) tree.get("trigger");
            trigger.put("cron", "every hour");
            trigger.put("timezone", "Mars/Olympus");
            trigger.remove("window");
            ((ObjectNode) tree.get("parameters").get("limit")).remove("default");
        });
        assertThat(problems).extracting(ValidationProblem::pointer).containsExactlyInAnyOrder(
                "#/trigger/cron", "#/trigger/timezone", "#/trigger/window", "#/parameters/limit");
    }

    @Test
    void theWindowReferenceMustNameADurationParameter() {
        List<ValidationProblem> problems = problems(tree ->
                ((ObjectNode) tree.get("trigger")).put("window", "${parameters.limit}"));
        assertThat(problems).singleElement().satisfies(p ->
                assertThat(p.message()).isEqualTo("Parameter \"limit\" must be a duration."));
    }

    @Test
    void executionStaysWithinTheOperatorsLimits() {
        List<ValidationProblem> problems = problems(tree -> {
            ObjectNode execution = (ObjectNode) tree.get("execution");
            execution.put("timeout", "PT2H");
            execution.put("memory", "8GB");
            execution.put("maxConcurrentRuns", 0);
        });
        assertThat(problems).extracting(ValidationProblem::pointer).containsExactlyInAnyOrder(
                "#/execution/timeout", "#/execution/memory", "#/execution/maxConcurrentRuns");
    }

    @Test
    void parameterDefaultsMustFitTheirType() {
        List<ValidationProblem> problems = problems(tree ->
                ((ObjectNode) tree.get("parameters").get("window")).put("default", "an hour"));
        assertThat(problems).singleElement().satisfies(p -> {
            assertThat(p.pointer()).isEqualTo("#/parameters/window/default");
            assertThat(p.message()).contains("ISO-8601 duration");
        });
    }
}
