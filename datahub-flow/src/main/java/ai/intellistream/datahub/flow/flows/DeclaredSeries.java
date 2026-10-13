// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.flows;

import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor;
import ai.intellistream.datahub.flow.definition.FlowDefinition;
import ai.intellistream.datahub.flow.definition.FlowDefinition.Connection;
import ai.intellistream.datahub.flow.definition.FlowDefinition.ProcessorSpec;
import ai.intellistream.datahub.flow.definition.Template;
import ai.intellistream.datahub.flow.definition.Template.Reference;
import ai.intellistream.datahub.flow.definition.ValidationProblem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The series a flow's timeseries sinks declare with {@code create: true}: worked out from the
 * definition when the flow is saved, so that the person saving it creates them, with their own
 * access, before any run. A run never creates a series.
 *
 * <p>So every target such a sink can produce must be known from the definition alone. Two forms are:
 * <ul>
 *   <li>a fixed external id, {@code "temp_f"};</li>
 *   <li>one built from the series it is handed, {@code "${timeseries.externalId}_f"}, where the
 *       series are the fixed lists of the {@code read-timeseries} processors upstream of
 *       the sink. Processors in between keep an item's {@code timeseries.externalId}.</li>
 * </ul>
 * Anything else — a parameter, another attribute, a source whose list is a reference — cannot be
 * known before a run and is refused when {@code create} is set. The name, unit and description may
 * use {@code ${timeseries.externalId}} the same way. A sink's unset properties take the sink's
 * defaults, so a sink with nothing set creates a series of its own beside each one read.
 */
public final class DeclaredSeries {

    static final String SINK = "write-timeseries";
    static final String SOURCE = "read-timeseries";
    static final String EXTERNAL_ID = "timeseries.externalId";

    private DeclaredSeries() {
    }

    /**
     * One series to create.
     *
     * @param unit     null when it is to be copied from {@code unitFrom}
     * @param unitFrom the series read that it is built from, when no unit is set; else null
     * @param dataSet  the external id of its dataset
     */
    public record Series(String externalId, String name, String unit, String unitFrom, String description,
                         String dataSet) {
    }

    /** What the sinks declare, or the problems that keep it from being worked out. */
    public record Result(List<Series> series, List<ValidationProblem> problems) {
    }

    /**
     * @param flowDataSet the flow's dataset, which created series go into unless a sink names one
     * @param sink        the sink's catalog entry, for the defaults of what a sink leaves unset;
     *                    null when the catalog has no sink
     */
    public static Result of(FlowDefinition definition, String flowDataSet, ProcessorDescriptor sink) {
        Map<String, Series> series = new LinkedHashMap<>();
        List<ValidationProblem> problems = new ArrayList<>();
        List<ProcessorSpec> processors = definition.processors();
        for (int i = 0; i < processors.size(); i++) {
            ProcessorSpec spec = processors.get(i);
            if (!SINK.equals(spec.type()) || !isTrue(property(spec, sink, "create"))) {
                continue;
            }
            String at = "#/processors/" + i + "/properties/";
            int problemsBefore = problems.size();
            JsonNode target = property(spec, sink, "target");
            if (target == null || !target.isString()) {
                continue; // the validator reports a missing or mistyped target
            }
            Template targetTemplate = template(target.asString(), at + "target", problems);
            if (targetTemplate == null) {
                continue;
            }
            List<String> inputs = List.of("");
            if (!targetTemplate.references().isEmpty()) {
                List<String> upstream = upstreamSeries(definition, spec.id(), at + "target", problems);
                if (upstream == null) {
                    continue;
                }
                inputs = upstream;
            }
            Template name = optional(spec, "name", at, problems);
            Template unit = optional(spec, "unit", at, problems);
            Template description = optional(spec, "description", at, problems);
            boolean copyUnit = spec.properties().get("unit") == null;
            if (copyUnit && targetTemplate.references().isEmpty()) {
                problems.add(new ValidationProblem(at + "unit", "Required with create and a fixed "
                        + "target: a series is created with a unit, and there is no series read to "
                        + "copy it from."));
            }
            if (problems.size() > problemsBefore) {
                continue;
            }
            JsonNode dataSetNode = spec.properties().get("dataSet");
            String dataSet = dataSetNode != null && dataSetNode.isString() ? dataSetNode.asString() : flowDataSet;
            for (String input : inputs) {
                String externalId = render(targetTemplate, input);
                series.putIfAbsent(externalId, new Series(externalId,
                        name == null ? externalId : render(name, input),
                        unit == null ? null : render(unit, input), copyUnit ? input : null,
                        description == null ? null : render(description, input), dataSet));
            }
        }
        return new Result(List.copyOf(series.values()), problems);
    }

    /** A template whose only reference, if any, is {@code ${timeseries.externalId}}; null if not. */
    private static Template template(String text, String pointer, List<ValidationProblem> problems) {
        Template template;
        try {
            template = Template.parse(text);
        } catch (IllegalArgumentException e) {
            return null; // the validator reports a malformed reference
        }
        for (Reference reference : template.references()) {
            if (!EXTERNAL_ID.equals(reference.name())) {
                problems.add(new ValidationProblem(pointer, "With create, only ${" + EXTERNAL_ID
                        + "} may be used here: the series are created when the flow is saved, before "
                        + "any run, so everything else in it must be fixed."));
                return null;
            }
        }
        return template;
    }

    private static Template optional(ProcessorSpec spec, String property, String at,
                                     List<ValidationProblem> problems) {
        JsonNode node = spec.properties().get(property);
        return node != null && node.isString() ? template(node.asString(), at + property, problems) : null;
    }

    /**
     * The fixed series lists of the sources upstream of {@code sinkId}, in definition order; null
     * when there is none, or one of them is not a fixed list.
     */
    private static List<String> upstreamSeries(FlowDefinition definition, String sinkId, String pointer,
                                               List<ValidationProblem> problems) {
        Map<String, List<String>> into = new LinkedHashMap<>();
        for (Connection c : definition.connections()) {
            if (c.to() != null && c.from() != null) {
                into.computeIfAbsent(c.to(), k -> new ArrayList<>()).add(c.from());
            }
        }
        Set<String> upstream = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(List.of(sinkId));
        while (!pending.isEmpty()) {
            for (String from : into.getOrDefault(pending.pop(), List.of())) {
                if (upstream.add(from)) {
                    pending.push(from);
                }
            }
        }
        Set<String> series = new LinkedHashSet<>();
        boolean anySource = false;
        List<ProcessorSpec> processors = definition.processors();
        for (int i = 0; i < processors.size(); i++) {
            ProcessorSpec spec = processors.get(i);
            if (!upstream.contains(spec.id()) || !SOURCE.equals(spec.type())) {
                continue;
            }
            anySource = true;
            JsonNode list = spec.properties().get("timeseries");
            if (list == null || !list.isArray() || !fixed(list)) {
                problems.add(new ValidationProblem("#/processors/" + i + "/properties/timeseries",
                        "Must be a fixed list of series: a sink downstream creates series from it."));
                return null;
            }
            list.forEach(node -> series.add(node.asString()));
        }
        if (!anySource) {
            problems.add(new ValidationProblem(pointer, "With create, ${" + EXTERNAL_ID + "} needs a "
                    + SOURCE + " upstream whose series it is built from."));
            return null;
        }
        return List.copyOf(series);
    }

    private static boolean fixed(JsonNode list) {
        for (JsonNode node : list) {
            if (!node.isString() || Template.mayReference(node.asString())) {
                return false;
            }
        }
        return true;
    }

    private static String render(Template template, String externalId) {
        return Template.text(template.substitute(reference -> externalId));
    }

    /** What a sink has set for {@code name}, or the sink's default; null when neither. */
    private static JsonNode property(ProcessorSpec spec, ProcessorDescriptor sink, String name) {
        JsonNode set = spec.properties().get(name);
        if (set != null) {
            return set;
        }
        PropertyDescriptor declared = sink == null ? null : sink.property(name);
        return declared == null || declared.defaultValue() == null ? null
                : JsonMapper.shared().valueToTree(declared.defaultValue());
    }

    private static boolean isTrue(JsonNode node) {
        return node != null && node.isBoolean() && node.booleanValue();
    }
}
