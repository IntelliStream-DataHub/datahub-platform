// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.engine;

import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor;
import ai.intellistream.datahub.flow.api.Relationship;
import ai.intellistream.datahub.flow.definition.ConfiguredValue;
import ai.intellistream.datahub.flow.definition.FlowDefinition;
import ai.intellistream.datahub.flow.definition.FlowDefinition.Connection;
import ai.intellistream.datahub.flow.definition.FlowDefinition.ProcessorSpec;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import ai.intellistream.datahub.flow.definition.InvalidFlowException;
import ai.intellistream.datahub.flow.definition.ValidationProblem;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns a flow definition into a {@link CompiledFlow}, validating it first. */
public final class FlowCompiler {

    private final FlowValidator validator;

    public FlowCompiler(FlowValidator validator) {
        this.validator = validator;
    }

    /**
     * @throws InvalidFlowException when the definition does not validate; secret references are not
     *                              checked here, since a missing secret fails the run instead
     */
    public CompiledFlow compile(FlowDefinition definition) {
        List<ValidationProblem> problems = validator.validate(definition, null);
        if (!problems.isEmpty()) {
            throw new InvalidFlowException(problems);
        }

        Map<String, List<String>> targets = new HashMap<>();   // "from\0relationship" → to
        Map<String, Integer> incoming = new HashMap<>();
        for (Connection c : definition.connections()) {
            targets.computeIfAbsent(c.from() + "\u0000" + c.relationship(), k -> new ArrayList<>())
                    .add(c.to());
            incoming.merge(c.to(), 1, Integer::sum);
        }

        Map<String, CompiledFlow.Node> nodes = new LinkedHashMap<>();
        for (ProcessorSpec spec : definition.processors()) {
            Processor processor = validator.processor(spec.type());
            ProcessorDescriptor descriptor = processor.describe();

            Map<String, ConfiguredValue> properties = new LinkedHashMap<>();
            Map<String, ConfiguredValue> dynamic = new LinkedHashMap<>();
            for (PropertyDescriptor p : descriptor.properties()) {
                if (!spec.properties().containsKey(p.name()) && p.defaultValue() != null) {
                    properties.put(p.name(), ConfiguredValue.of(p.name(), p.defaultValue()));
                }
            }
            spec.properties().forEach((name, node) -> {
                ConfiguredValue value = ConfiguredValue.of(name, ConfiguredValue.fromJson(node));
                (descriptor.property(name) != null ? properties : dynamic).put(name, value);
            });

            Map<String, CompiledFlow.Outcome> outcomes = new LinkedHashMap<>();
            for (Relationship r : descriptor.relationships()) {
                outcomes.put(r.name(), new CompiledFlow.Outcome(
                        List.copyOf(targets.getOrDefault(spec.id() + "\u0000" + r.name(), List.of())),
                        spec.terminate().contains(r.name()), r.whenUnconnected()));
            }

            int maxAttempts = spec.retry() == null || spec.retry().maxAttempts() == null
                    ? 1 : spec.retry().maxAttempts();
            Duration backoff = spec.retry() == null || spec.retry().backoff() == null
                    ? Duration.ZERO : Duration.parse(spec.retry().backoff());
            nodes.put(spec.id(), new CompiledFlow.Node(spec.id(), processor, properties, dynamic,
                    outcomes, maxAttempts, backoff, !incoming.containsKey(spec.id())));
        }
        return new CompiledFlow(definition, topologicalOrder(nodes, incoming));
    }

    /** Kahn's algorithm, taking ready processors in definition order so the result is stable. */
    private static List<CompiledFlow.Node> topologicalOrder(Map<String, CompiledFlow.Node> nodes,
                                                            Map<String, Integer> incoming) {
        Map<String, Integer> waiting = new HashMap<>(incoming);
        List<CompiledFlow.Node> order = new ArrayList<>(nodes.size());
        Deque<CompiledFlow.Node> ready = new ArrayDeque<>();
        for (CompiledFlow.Node n : nodes.values()) {
            if (!waiting.containsKey(n.id())) {
                ready.add(n);
            }
        }
        while (!ready.isEmpty()) {
            CompiledFlow.Node n = ready.poll();
            order.add(n);
            List<String> next = new ArrayList<>();
            for (CompiledFlow.Outcome o : n.outcomes().values()) {
                next.addAll(o.targets());
            }
            for (CompiledFlow.Node candidate : nodes.values()) {
                int edges = 0;
                for (String t : next) {
                    if (t.equals(candidate.id())) {
                        edges++;
                    }
                }
                if (edges > 0 && waiting.merge(candidate.id(), -edges, Integer::sum) == 0) {
                    waiting.remove(candidate.id());
                    ready.add(candidate);
                }
            }
        }
        return order;
    }
}
