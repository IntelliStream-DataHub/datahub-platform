// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.engine;

import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.Relationship;
import ai.intellistream.datahub.flow.definition.ConfiguredValue;
import ai.intellistream.datahub.flow.definition.FlowDefinition;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A flow definition as the engine runs it: processors resolved, defaults applied, outcomes wired.
 * Built once per flow version by {@link FlowCompiler} and shared by every run of that version, since
 * a version never changes.
 *
 * @param nodes the processors in topological order: every processor after all that feed it, and
 *              otherwise in definition order
 */
public record CompiledFlow(FlowDefinition definition, List<Node> nodes) {

    public CompiledFlow {
        nodes = List.copyOf(nodes);
    }

    /**
     * One processor in the flow.
     *
     * @param properties        declared properties, set or by default, as written
     * @param dynamicProperties user-named properties, as written, in definition order
     * @param outcomes          every relationship the processor has, by name
     * @param maxAttempts       attempts of one invocation that throws, the first included
     * @param source            nothing is connected into it: invoked once per run
     */
    public record Node(String id, Processor processor, Map<String, ConfiguredValue> properties,
                       Map<String, ConfiguredValue> dynamicProperties, Map<String, Outcome> outcomes,
                       int maxAttempts, Duration backoff, boolean source) {

        public String type() {
            return processor.describe().name();
        }
    }

    /**
     * Where items sent along one relationship go.
     *
     * @param targets         the processors it is connected to; a copy of the item goes to each
     * @param terminated      listed in the processor's {@code terminate}
     * @param whenUnconnected what the processor declares for it when neither connected nor
     *                        terminated
     */
    public record Outcome(List<String> targets, boolean terminated,
                          Relationship.Unconnected whenUnconnected) {
    }
}
