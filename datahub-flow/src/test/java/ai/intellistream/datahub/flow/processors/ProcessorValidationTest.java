// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.flow.definition.FlowDefinitions;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import ai.intellistream.datahub.flow.definition.ValidationProblem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The checks a processor makes on its own combination of values when a flow is saved. */
class ProcessorValidationTest {

    private static List<String> problems(String type, String properties, String terminate) {
        String json = """
                {
                  "schemaVersion": 1, "externalId": "t", "dataSet": "ds", "name": "T",
                  "processors": [
                    { "id": "src", "type": "timeseries.read",
                      "properties": { "timeseries": ["ts1"] } },
                    { "id": "p", "type": "%s", "properties": %s, "terminate": %s }
                  ],
                  "connections": [ { "from": "src", "relationship": "success", "to": "p" } ]
                }
                """.formatted(type, properties, terminate);
        return new FlowValidator(Harness.builtIns(), FlowValidator.Limits.DEFAULTS)
                .validate(FlowDefinitions.parse(json), null).stream()
                .map(ValidationProblem::message).toList();
    }

    @Test
    void clipAndFilterRangeNeedABound() {
        assertThat(problems("timeseries.clip", "{}", "[\"success\"]"))
                .contains("Set min, max or both.");
        assertThat(problems("timeseries.filter.range", "{ \"min\": 5, \"max\": 1 }",
                "[\"inRange\", \"outOfRange\"]"))
                .contains("min must not be greater than max.");
        assertThat(problems("timeseries.clip", "{ \"min\": 0 }", "[\"success\"]")).isEmpty();
    }

    @Test
    void aBoundThatIsAReferenceIsCheckedAtRunTime() {
        assertThat(problems("timeseries.clip", "{ \"min\": \"${parameters.low}\", \"max\": 1 }",
                "[\"success\"]")).noneMatch(p -> p.contains("greater"));
    }

    @Test
    void theSinksCreationFieldsNeedCreate() {
        assertThat(problems("timeseries.write",
                "{ \"target\": \"x\", \"create\": false, \"unit\": \"°F\" }", "[]"))
                .contains("unit is only used with create.");
        // create is on by default.
        assertThat(problems("timeseries.write", "{ \"target\": \"x\", \"unit\": \"°F\" }", "[]"))
                .noneMatch(p -> p.contains("only used with create"));
    }

    @Test
    void durationsMustBePositive() {
        assertThat(problems("timeseries.smooth", "{ \"window\": \"PT0S\" }", "[\"success\"]"))
                .contains("window must be at least a millisecond.");
        assertThat(problems("timeseries.interpolate", "{ \"interval\": \"PT5M\", \"maxGap\": \"PT1M\" }",
                "[\"success\"]")).contains("maxGap must be longer than interval.");
        assertThat(problems("timeseries.despike", "{ \"window\": \"PT5M\", \"threshold\": 0 }",
                "[\"success\"]")).contains("threshold must be greater than 0.");
    }
}
