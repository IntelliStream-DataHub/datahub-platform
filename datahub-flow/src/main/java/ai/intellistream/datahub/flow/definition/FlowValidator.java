// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.definition;

import ai.intellistream.datahub.flow.api.DynamicPropertyDescriptor;
import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor;
import ai.intellistream.datahub.flow.api.PropertyType;
import ai.intellistream.datahub.flow.api.PropertyValue;
import ai.intellistream.datahub.flow.api.PropertyValues;
import ai.intellistream.datahub.flow.api.Relationship;
import ai.intellistream.datahub.flow.definition.FlowDefinition.Connection;
import ai.intellistream.datahub.flow.definition.FlowDefinition.ProcessorSpec;
import ai.intellistream.datahub.flow.definition.Template.Reference;
import ai.intellistream.datahub.flow.definition.Template.Scope;
import org.springframework.scheduling.support.CronExpression;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a flow definition before it is saved: its structure, its wiring, and every property
 * against the processor that declares it. Content kinds are not checked: a processor connected to
 * input it cannot handle fails at run time.
 *
 * <p>Reports everything wrong at once, each problem with a JSON Pointer to where it is.
 */
public final class FlowValidator {

    /** Operator limits a flow's execution settings must stay within. */
    public record Limits(Duration maxTimeout) {
        public static final Limits DEFAULTS = new Limits(Duration.ofHours(1));
    }

    static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,254}");
    static final Pattern PROCESSOR_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    static final int MAX_RETRY_ATTEMPTS = 10;

    /** The types a flow parameter may have. */
    static final Set<PropertyType> PARAMETER_TYPES = Set.of(PropertyType.STRING, PropertyType.NUMBER,
            PropertyType.BOOLEAN, PropertyType.DURATION, PropertyType.INSTANT, PropertyType.LIST);

    private final Map<String, Processor> processors = new HashMap<>();
    private final Limits limits;

    public FlowValidator(Collection<Processor> processors, Limits limits) {
        for (Processor p : processors) {
            this.processors.put(p.describe().name(), p);
        }
        this.limits = limits;
    }

    /**
     * Everything wrong with {@code definition}; empty when it is valid.
     *
     * @param knownSecrets the names of the tenant's flow secrets, or null to leave secret
     *                     references unchecked
     */
    public List<ValidationProblem> validate(FlowDefinition definition, Set<String> knownSecrets) {
        Check check = new Check(definition, knownSecrets);
        check.all();
        return check.problems;
    }

    /** The processor a definition's {@code type} names, or null. */
    public Processor processor(String type) {
        return processors.get(type);
    }

    /** A processor's relationships by name. */
    public static Map<String, Relationship> relationships(ProcessorDescriptor descriptor) {
        Map<String, Relationship> all = new LinkedHashMap<>();
        for (Relationship r : descriptor.relationships()) {
            all.put(r.name(), r);
        }
        return all;
    }

    /**
     * {@code raw} as a value of {@code type}: a {@link BigDecimal}, {@link Boolean},
     * {@link Duration}, {@link Instant}, a list of strings, or a string.
     *
     * @throws IllegalArgumentException with the reason, when it is not one
     */
    public static Object typed(PropertyType type, Object raw) {
        return switch (type) {
            case STRING, ENUM, SECRET -> {
                if (raw instanceof String s) {
                    yield s;
                }
                throw new IllegalArgumentException("must be a string");
            }
            case NUMBER -> {
                if (raw instanceof BigDecimal d) {
                    yield d;
                }
                if (raw instanceof Number n) {
                    yield new BigDecimal(n.toString());
                }
                throw new IllegalArgumentException("must be a number");
            }
            case BOOLEAN -> {
                if (raw instanceof Boolean b) {
                    yield b;
                }
                throw new IllegalArgumentException("must be true or false");
            }
            case DURATION -> {
                if (raw instanceof Duration d) {
                    yield d;
                }
                try {
                    if (raw instanceof String s) {
                        yield Duration.parse(s);
                    }
                } catch (DateTimeParseException e) {
                    // fall through to the message below
                }
                throw new IllegalArgumentException("must be an ISO-8601 duration such as PT5M");
            }
            case INSTANT -> {
                if (raw instanceof Instant i) {
                    yield i;
                }
                try {
                    if (raw instanceof String s) {
                        yield Instant.parse(s);
                    }
                } catch (DateTimeParseException e) {
                    // fall through to the message below
                }
                throw new IllegalArgumentException(
                        "must be an ISO-8601 instant such as 2026-10-01T00:00:00Z");
            }
            case LIST -> {
                if (raw instanceof List<?> list) {
                    List<String> out = new ArrayList<>(list.size());
                    for (Object element : list) {
                        if (!(element instanceof String s)) {
                            throw new IllegalArgumentException("must be a list of strings");
                        }
                        out.add(s);
                    }
                    yield List.copyOf(out);
                }
                throw new IllegalArgumentException("must be a list of strings");
            }
        };
    }

    /** One validation pass, collecting problems as it goes. */
    private final class Check {
        private final FlowDefinition d;
        private final Set<String> knownSecrets;
        private final List<ValidationProblem> problems = new ArrayList<>();
        /** Processor id → its relationships, for those whose type is known. */
        private final Map<String, Map<String, Relationship>> relationshipsById = new HashMap<>();

        Check(FlowDefinition d, Set<String> knownSecrets) {
            this.d = d;
            this.knownSecrets = knownSecrets;
        }

        void problem(String pointer, String message) {
            problems.add(new ValidationProblem(pointer, message));
        }

        void all() {
            header();
            parameters();
            processors();
            connections();
            trigger();
            execution();
        }

        private void header() {
            if (d.schemaVersion() == null || d.schemaVersion() != 1) {
                problem("#/schemaVersion", "Must be 1, the only definition format there is.");
            }
            if (d.externalId() == null || !EXTERNAL_ID.matcher(d.externalId()).matches()) {
                problem("#/externalId", "Required: letters, digits and _ . : -, starting with a "
                        + "letter or digit, at most 255 characters.");
            }
            if (d.dataSet() == null || d.dataSet().isBlank()) {
                problem("#/dataSet", "Required: the external id of the dataset the flow belongs to.");
            }
            if (d.name() == null || d.name().isBlank() || d.name().length() > 255) {
                problem("#/name", "Required, at most 255 characters.");
            }
        }

        private void parameters() {
            d.parameters().forEach((name, parameter) -> {
                String at = "#/parameters/" + name;
                if (!NAME.matcher(name).matches()) {
                    problem(at, "A parameter name is letters, digits and _, starting with a letter.");
                }
                PropertyType type = parameterType(parameter.type());
                if (type == null) {
                    problem(at + "/type", "Must be one of string, number, boolean, duration, "
                            + "instant, list.");
                    return;
                }
                if (parameter.defaultValue() != null) {
                    try {
                        typed(type, ConfiguredValue.fromJson(parameter.defaultValue()));
                    } catch (IllegalArgumentException e) {
                        problem(at + "/default", "The default " + e.getMessage() + ".");
                    }
                }
            });
        }

        private void processors() {
            if (d.processors().isEmpty()) {
                problem("#/processors", "A flow needs at least one processor.");
            }
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < d.processors().size(); i++) {
                ProcessorSpec spec = d.processors().get(i);
                String at = "#/processors/" + i;
                if (spec.id() == null || !PROCESSOR_ID.matcher(spec.id()).matches()) {
                    problem(at + "/id", "Required: letters, digits, _ and -, starting with a "
                            + "letter, at most 64 characters.");
                } else if (!ids.add(spec.id())) {
                    problem(at + "/id", "\"" + spec.id() + "\" is used by another processor.");
                }
                Processor processor = spec.type() == null ? null : processors.get(spec.type());
                if (processor == null) {
                    problem(at + "/type", spec.type() == null ? "Required."
                            : "No processor is called \"" + spec.type() + "\".");
                    continue;
                }
                ProcessorDescriptor descriptor = processor.describe();
                if (spec.id() != null) {
                    relationshipsById.put(spec.id(), relationships(descriptor));
                }
                properties(at, spec, processor, descriptor);
                retry(at, spec);
            }
        }

        private void properties(String at, ProcessorSpec spec, Processor processor,
                                ProcessorDescriptor descriptor) {
            Map<String, PropertyValue> declared = new LinkedHashMap<>();
            Map<String, PropertyValue> dynamic = new LinkedHashMap<>();
            DynamicPropertyDescriptor dynamicDescriptor = descriptor.dynamicProperties();

            spec.properties().forEach((name, node) -> {
                String where = at + "/properties/" + Template.escapePointer(name);
                PropertyDescriptor property = descriptor.property(name);
                if (property == null && dynamicDescriptor == null) {
                    problem(where, "\"" + descriptor.name() + "\" has no property \"" + name + "\".");
                    return;
                }
                PropertyType type = property != null ? property.type() : dynamicDescriptor.type();
                boolean references = property != null ? property.references()
                        : dynamicDescriptor.references();
                boolean sensitive = property != null && property.sensitive();
                ConfiguredValue value = value(where, name, node, type, references, sensitive,
                        property);
                if (value != null) {
                    (property != null ? declared : dynamic).put(name, value);
                }
            });

            for (PropertyDescriptor property : descriptor.properties()) {
                if (spec.properties().containsKey(property.name())) {
                    continue;
                }
                if (property.defaultValue() != null) {
                    declared.put(property.name(),
                            ConfiguredValue.of(property.name(), property.defaultValue()));
                } else if (property.required()) {
                    problem(at + "/properties/" + Template.escapePointer(property.name()),
                            "Required.");
                }
            }

            PropertyValues values = new PropertyValues() {
                @Override
                public PropertyValue property(String name) {
                    PropertyValue v = declared.get(name);
                    return v != null ? v : ConfiguredValue.of(name, null);
                }

                @Override
                public Map<String, PropertyValue> dynamicProperties() {
                    return dynamic;
                }
            };
            try {
                for (String message : processor.validate(values)) {
                    problem(at + "/properties", message);
                }
            } catch (RuntimeException e) {
                problem(at + "/properties", "Could not be checked: " + e.getMessage());
            }
        }

        /** The value checked against its declaration, or null when it is not usable. */
        private ConfiguredValue value(String where, String name, JsonNode node, PropertyType type,
                                      boolean references, boolean sensitive,
                                      PropertyDescriptor property) {
            Object raw;
            try {
                raw = ConfiguredValue.fromJson(node);
            } catch (IllegalArgumentException e) {
                problem(where, e.getMessage() + ".");
                return null;
            }
            ConfiguredValue value;
            try {
                value = ConfiguredValue.of(name, raw);
            } catch (IllegalArgumentException e) {
                problem(where, e.getMessage() + ".");
                return null;
            }
            Object parsed = value.value();
            boolean secret = type == PropertyType.SECRET || sensitive;

            if (secret) {
                if (!(parsed instanceof Template t) || !t.isSingleReference()
                        || t.references().getFirst().scope() != Scope.SECRETS) {
                    problem(where, "Must be a reference to a secret, ${secrets.<name>}: a secret's "
                            + "value never goes in a definition.");
                    return null;
                }
                references(where, t, true);
                return value;
            }

            if (parsed instanceof List<?> list) {
                if (type != PropertyType.LIST) {
                    problem(where, "Must not be a list.");
                    return null;
                }
                for (int i = 0; i < list.size(); i++) {
                    Object element = list.get(i);
                    if (element instanceof Template t) {
                        if (!references) {
                            problem(where + "/" + i, "This property does not take references.");
                        } else {
                            references(where + "/" + i, t, false);
                        }
                    } else if (!(element instanceof String)) {
                        problem(where + "/" + i, "Must be a string.");
                    }
                }
                return value;
            }

            if (parsed instanceof Template t) {
                if (!references) {
                    problem(where, "This property does not take references.");
                    return null;
                }
                references(where, t, false);
                if (!t.isSingleReference() && type != PropertyType.STRING) {
                    problem(where, "Must be a literal value or a single reference: text with a "
                            + "reference in it is only allowed for a string property.");
                }
                return value;
            }

            try {
                Object typedValue = typed(type, parsed);
                if (type == PropertyType.ENUM && property != null && property.allowableValues()
                        .stream().noneMatch(a -> a.value().equals(typedValue))) {
                    List<String> allowed = property.allowableValues().stream()
                            .map(PropertyDescriptor.AllowableValue::value).toList();
                    problem(where, "Must be one of " + String.join(", ", allowed) + ".");
                    return null;
                }
            } catch (IllegalArgumentException e) {
                problem(where, "Must be " + e.getMessage().substring("must be ".length()) + ".");
                return null;
            }
            return value;
        }

        /**
         * Checks that every reference in {@code t} names something that exists. A secret may only
         * be referred to from a secret property: anywhere else its value could end up in an
         * attribute, a log line or a transit URI.
         */
        private void references(String where, Template t, boolean secretProperty) {
            for (Reference r : t.references()) {
                if (r.scope() == Scope.SECRETS && !secretProperty) {
                    problem(where, "${" + r.name() + "}: a secret can only be used in a property "
                            + "that is declared secret or sensitive.");
                    continue;
                }
                switch (r.scope()) {
                    case PARAMETERS -> {
                        if (!d.parameters().containsKey(r.key())) {
                            problem(where, "${" + r.name() + "}: the flow has no parameter \""
                                    + r.key() + "\".");
                        }
                    }
                    case RUN -> {
                        if (!Template.RUN_KEYS.contains(r.key())) {
                            problem(where, "${" + r.name() + "}: a run has "
                                    + String.join(", ", sorted(Template.RUN_KEYS, "run.")) + ".");
                        }
                    }
                    case ITEM -> {
                        if (!Template.ITEM_KEYS.contains(r.key())) {
                            problem(where, "${" + r.name() + "}: an item's summary has "
                                    + String.join(", ", sorted(Template.ITEM_KEYS, "item.")) + ".");
                        }
                    }
                    case SECRETS -> {
                        if (knownSecrets != null && !knownSecrets.contains(r.key())) {
                            problem(where, "${" + r.name() + "}: the organization has no flow "
                                    + "secret called \"" + r.key() + "\".");
                        }
                    }
                    case ATTRIBUTE -> {
                        // Any attribute may be referred to; a missing one fails the item.
                    }
                }
            }
        }

        private void retry(String at, ProcessorSpec spec) {
            if (spec.retry() == null) {
                return;
            }
            Integer attempts = spec.retry().maxAttempts();
            if (attempts != null && (attempts < 1 || attempts > MAX_RETRY_ATTEMPTS)) {
                problem(at + "/retry/maxAttempts", "Must be between 1 and " + MAX_RETRY_ATTEMPTS + ".");
            }
            if (spec.retry().backoff() != null) {
                Duration backoff = duration(at + "/retry/backoff", spec.retry().backoff());
                if (backoff != null && (backoff.isNegative() || backoff.compareTo(Duration.ofMinutes(1)) > 0)) {
                    problem(at + "/retry/backoff", "Must be between PT0S and PT1M.");
                }
            }
        }

        private void connections() {
            Map<String, Set<String>> edges = new LinkedHashMap<>();
            Map<String, Set<String>> connected = new HashMap<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < d.connections().size(); i++) {
                Connection c = d.connections().get(i);
                String at = "#/connections/" + i;
                if (Boolean.TRUE.equals(c.durable())) {
                    problem(at + "/durable", "Durable connections are not available yet.");
                }
                boolean fromKnown = endpoint(at + "/from", c.from());
                boolean toKnown = endpoint(at + "/to", c.to());
                if (!fromKnown) {
                    continue;
                }
                Map<String, Relationship> relationships = relationshipsById.get(c.from());
                if (c.relationship() == null) {
                    problem(at + "/relationship", "Required.");
                } else if (relationships != null && !relationships.containsKey(c.relationship())) {
                    problem(at + "/relationship", "\"" + c.from() + "\" has no relationship \""
                            + c.relationship() + "\"; it has " + String.join(", ",
                            relationships.keySet()) + ".");
                } else if (!seen.add(c.from() + "\u0000" + c.relationship() + "\u0000" + c.to())) {
                    problem(at, "This connection is listed twice.");
                }
                if (toKnown && c.relationship() != null) {
                    edges.computeIfAbsent(c.from(), k -> new LinkedHashSet<>()).add(c.to());
                    connected.computeIfAbsent(c.from(), k -> new HashSet<>()).add(c.relationship());
                }
            }
            outcomes(connected);
            cycles(edges);
        }

        /** Whether {@code id} names a processor in the flow; reports it when not. */
        private boolean endpoint(String at, String id) {
            if (id == null) {
                problem(at, "Required.");
                return false;
            }
            for (ProcessorSpec spec : d.processors()) {
                if (id.equals(spec.id())) {
                    return true;
                }
            }
            problem(at, "No processor has the id \"" + id + "\".");
            return false;
        }

        /**
         * Every relationship connected, terminated, or allowed to be neither; one that must be
         * connected is never terminated.
         */
        private void outcomes(Map<String, Set<String>> connected) {
            for (int i = 0; i < d.processors().size(); i++) {
                ProcessorSpec spec = d.processors().get(i);
                Map<String, Relationship> relationships = relationshipsById.get(spec.id());
                if (relationships == null) {
                    continue;
                }
                Set<String> wired = connected.getOrDefault(spec.id(), Set.of());
                for (int t = 0; t < spec.terminate().size(); t++) {
                    String name = spec.terminate().get(t);
                    String at = "#/processors/" + i + "/terminate/" + t;
                    if (!relationships.containsKey(name)) {
                        problem(at, "\"" + spec.id() + "\" has no relationship \"" + name + "\".");
                    } else if (wired.contains(name)) {
                        problem(at, "\"" + name + "\" is both connected and terminated.");
                    } else if (relationships.get(name).whenUnconnected()
                            == Relationship.Unconnected.MUST_CONNECT) {
                        problem(at, "\"" + name + "\" cannot be ended there: it is what \""
                                + spec.id() + "\" produces. Connect it to another processor.");
                    }
                }
                for (Relationship r : relationships.values()) {
                    if (r.whenUnconnected() == Relationship.Unconnected.MUST_CONNECT
                            && !wired.contains(r.name()) && !spec.terminate().contains(r.name())) {
                        problem("#/processors/" + i, "Its \"" + r.name() + "\" outcome goes nowhere: "
                                + "connect it to another processor.");
                    }
                    if (r.whenUnconnected() == Relationship.Unconnected.REQUIRED
                            && !wired.contains(r.name()) && !spec.terminate().contains(r.name())) {
                        problem("#/processors/" + i, "Its \"" + r.name() + "\" outcome goes nowhere: "
                                + "connect it to another processor, or end it there (terminate).");
                    }
                }
            }
        }

        private void cycles(Map<String, Set<String>> edges) {
            Map<String, Integer> state = new HashMap<>();   // 1 visiting, 2 done
            for (String start : edges.keySet()) {
                if (visit(start, edges, state)) {
                    problem("#/connections", "The connections form a cycle through \"" + start
                            + "\"; a flow must not loop.");
                    return;
                }
            }
        }

        private boolean visit(String node, Map<String, Set<String>> edges, Map<String, Integer> state) {
            Integer s = state.get(node);
            if (s != null) {
                return s == 1;
            }
            state.put(node, 1);
            for (String next : edges.getOrDefault(node, Set.of())) {
                if (visit(next, edges, state)) {
                    return true;
                }
            }
            state.put(node, 2);
            return false;
        }

        private void trigger() {
            FlowDefinition.Trigger trigger = d.trigger();
            if (trigger == null) {
                return;
            }
            if (!"manual".equals(trigger.type()) && !"schedule".equals(trigger.type())) {
                problem("#/trigger/type", "Must be manual or schedule.");
                return;
            }
            boolean schedule = "schedule".equals(trigger.type());
            if (schedule) {
                if (trigger.cron() == null || !CronExpression.isValidExpression(trigger.cron())) {
                    problem("#/trigger/cron", "Required for a schedule: a six-field cron "
                            + "expression such as \"0 0 * * * *\" (seconds first).");
                }
                if (trigger.timezone() != null) {
                    try {
                        ZoneId.of(trigger.timezone());
                    } catch (DateTimeException e) {
                        problem("#/trigger/timezone", "Not a time zone: \"" + trigger.timezone() + "\".");
                    }
                }
                for (Map.Entry<String, FlowDefinition.Parameter> p : d.parameters().entrySet()) {
                    if (p.getValue().defaultValue() == null) {
                        problem("#/parameters/" + p.getKey(), "A scheduled flow's parameters need "
                                + "defaults: nobody supplies them when a tick starts a run.");
                    }
                }
            } else if (trigger.cron() != null || trigger.timezone() != null || trigger.delay() != null) {
                problem("#/trigger", "cron, timezone and delay only apply to a schedule.");
            }
            if (trigger.window() != null) {
                windowDuration(trigger.window());
            }
            if (trigger.delay() != null) {
                Duration delay = duration("#/trigger/delay", trigger.delay());
                if (delay != null && delay.isNegative()) {
                    problem("#/trigger/delay", "Must not be negative.");
                }
            }
        }

        /** The trigger's window: a positive duration, or a reference to a duration parameter. */
        private void windowDuration(String window) {
            String at = "#/trigger/window";
            if (Template.mayReference(window)) {
                Template t;
                try {
                    t = Template.parse(window);
                } catch (IllegalArgumentException e) {
                    problem(at, e.getMessage() + ".");
                    return;
                }
                if (!t.isSingleReference() || t.references().getFirst().scope() != Scope.PARAMETERS) {
                    problem(at, "Must be a duration or a single ${parameters.<name>} reference.");
                    return;
                }
                FlowDefinition.Parameter p = d.parameters().get(t.references().getFirst().key());
                if (p == null) {
                    problem(at, "The flow has no parameter \"" + t.references().getFirst().key() + "\".");
                } else if (parameterType(p.type()) != PropertyType.DURATION) {
                    problem(at, "Parameter \"" + t.references().getFirst().key()
                            + "\" must be a duration.");
                }
                return;
            }
            Duration d = duration(at, window);
            if (d != null && (d.isZero() || d.isNegative())) {
                problem(at, "Must be positive.");
            }
        }

        private void execution() {
            FlowDefinition.Execution e = d.execution();
            if (e == null) {
                return;
            }
            if (e.maxConcurrentRuns() != null && e.maxConcurrentRuns() < 1) {
                problem("#/execution/maxConcurrentRuns", "Must be at least 1.");
            }
            if (e.maxAttempts() != null && (e.maxAttempts() < 1 || e.maxAttempts() > MAX_RETRY_ATTEMPTS)) {
                problem("#/execution/maxAttempts", "Must be between 1 and " + MAX_RETRY_ATTEMPTS + ".");
            }
            if (e.timeout() != null) {
                Duration timeout = duration("#/execution/timeout", e.timeout());
                if (timeout != null && (timeout.isZero() || timeout.isNegative()
                        || timeout.compareTo(limits.maxTimeout()) > 0)) {
                    problem("#/execution/timeout", "Must be positive and at most "
                            + limits.maxTimeout() + ".");
                }
            }
        }

        private Duration duration(String at, String text) {
            try {
                return Duration.parse(text);
            } catch (DateTimeParseException e) {
                problem(at, "Must be an ISO-8601 duration such as PT5M.");
                return null;
            }
        }

        private static List<String> sorted(Set<String> keys, String prefix) {
            return keys.stream().sorted().map(k -> prefix + k).toList();
        }
    }

    /** A parameter's declared type, or null when it names no parameter type. */
    public static PropertyType parameterType(String type) {
        if (type == null) {
            return null;
        }
        for (PropertyType t : PARAMETER_TYPES) {
            if (t.name().equalsIgnoreCase(type)) {
                return t;
            }
        }
        return null;
    }
}
