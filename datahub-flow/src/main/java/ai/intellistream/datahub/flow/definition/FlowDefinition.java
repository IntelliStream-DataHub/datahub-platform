// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.definition;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * A flow as written: JSON, parsed by {@link FlowDefinitions}, checked by {@link FlowValidator}.
 *
 * <p>Fields absent from the JSON are null here (an empty map or list for the collections); the
 * validator, not the parser, decides what is required and what defaults apply. Property values stay
 * JSON until the flow is compiled, since what they mean depends on the processor's declaration.
 *
 * @param schemaVersion the definition format's version; only {@code 1} exists
 * @param externalId    the flow's stable id within the tenant
 * @param dataSet       external id of the dataset the flow belongs to, which decides who sees it
 * @param parameters    values a run is given, by name, in the order written
 * @param processors    the steps, each with an id unique in the flow
 * @param connections   edges from a processor's relationship to the next processor
 * @param trigger       when runs start; null means manual only
 * @param execution     limits; null means the defaults
 */
public record FlowDefinition(Integer schemaVersion, String externalId, String dataSet, String name,
                             String description, Map<String, Parameter> parameters,
                             List<ProcessorSpec> processors, List<Connection> connections,
                             Trigger trigger, Execution execution) {

    public FlowDefinition {
        parameters = parameters == null ? Map.of() : parameters;
        processors = processors == null ? List.of() : processors;
        connections = connections == null ? List.of() : connections;
    }

    /**
     * A flow parameter: {@code ${parameters.<name>}} in a property or the trigger's window.
     *
     * @param type one of {@code string}, {@code number}, {@code boolean}, {@code duration},
     *             {@code instant}, {@code list}
     */
    public record Parameter(String type, @JsonProperty("default") JsonNode defaultValue,
                            String description) {
    }

    /**
     * One step.
     *
     * @param id         unique in the flow; connections and records refer to it
     * @param type       the processor's catalog name, e.g. {@code clip}
     * @param properties property values by name, declared ones and dynamic ones together
     * @param retry      retries of a single invocation that throws, within the run
     * @param terminate  relationships whose items end here
     */
    public record ProcessorSpec(String id, String type, Map<String, JsonNode> properties,
                                Retry retry, List<String> terminate) {

        public ProcessorSpec {
            properties = properties == null ? Map.of() : properties;
            terminate = terminate == null ? List.of() : terminate;
        }
    }

    /**
     * @param maxAttempts attempts of one invocation, the first included; default 1
     * @param backoff     ISO-8601 duration to wait between attempts; default none
     */
    public record Retry(Integer maxAttempts, String backoff) {
    }

    /**
     * @param durable accepted in the format so that it can be implemented later (a Pulsar topic
     *                between the two processors); rejected by the validator until then
     */
    public record Connection(String from, String relationship, String to, Boolean durable) {
    }

    /**
     * @param type     {@code manual} or {@code schedule}
     * @param cron     for a schedule: Spring's six-field cron expression
     * @param timezone for a schedule: the zone the cron expression is read in
     * @param window   ISO-8601 duration, or a {@code ${parameters.<name>}} reference to one: the tick
     *                 at T covers {@code [T - window, T)}. Optional for a schedule, whose tick at T
     *                 then covers the time since the tick before it
     * @param delay    ISO-8601 duration after the tick before the run starts, so late data arrives
     */
    public record Trigger(String type, String cron, String timezone, String window, String delay) {
    }

    /**
     * What a run may use. How much memory it may hold is not here: every run gets an equal share
     * of the instance it runs on, sized by the operator.
     *
     * @param timeout ISO-8601 duration
     */
    public record Execution(Integer maxConcurrentRuns, Integer maxAttempts, String timeout) {
    }
}
