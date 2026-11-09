// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.engine;

import ai.intellistream.datahub.flow.api.EventMatch;
import ai.intellistream.datahub.flow.api.Item;
import ai.intellistream.datahub.flow.api.ProcessContext;
import ai.intellistream.datahub.flow.api.ProcessException;
import ai.intellistream.datahub.flow.api.PropertyType;
import ai.intellistream.datahub.flow.api.PropertyValue;
import ai.intellistream.datahub.flow.api.RunLog;
import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.api.TimeseriesContent;
import ai.intellistream.datahub.flow.definition.ConfiguredValue;
import ai.intellistream.datahub.flow.definition.FlowDefinition;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import ai.intellistream.datahub.flow.definition.Template.Reference;
import ai.intellistream.datahub.flow.definition.Template.Scope;
import ai.intellistream.datahub.flow.engine.RunResult.LogLine;
import ai.intellistream.datahub.flow.engine.RunResult.Status;
import ai.intellistream.datahub.sdk.client.DatahubClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * Executes one attempt of a run, in memory, on the calling thread.
 *
 * <p>Sources are invoked once each. After that, the engine keeps invoking processors whose queue
 * holds items until every queue is empty — always the furthest-downstream processor with work, so
 * items flow on to the sinks and their memory is released as early as possible, rather than every
 * step's output piling up before the next step starts.
 *
 * <p>Each invocation is a transaction ({@link RunSession}). One that throws is rolled back and
 * retried as the processor's {@code retry} allows; when its attempts are used up the run fails. A
 * run that goes over its memory cap fails at once, and alone: every series a processor builds is
 * counted against the run's own cap before it is allocated, so the error stays inside the run.
 *
 * <p>Whatever happens, everything the run counted is given back before {@link #execute} returns.
 */
public final class RunExecutor {

    /** Waits between attempts of an invocation; a test passes one that does not. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final Clock clock;
    private final Sleeper sleeper;

    public RunExecutor(Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public RunResult execute(CompiledFlow flow, RunRequest request, RecordSink sink) {
        List<LogLine> log = new ArrayList<>();
        Set<String> secretsUsed = new TreeSet<>();
        Map<String, String> versions = new TreeMap<>();
        for (CompiledFlow.Node node : flow.nodes()) {
            versions.put(node.type(), node.processor().describe().version());
        }

        Map<String, Object> parameters = Map.of();
        Status status;
        String code = null;
        String error = null;
        String failedProcessor = null;
        Run run = new Run(request.runId(), request.attempt(), request.memoryBytes(), clock, sink);
        try {
            parameters = parameters(flow.definition(), request.parameters());
            Map<String, NodeContext> contexts = new LinkedHashMap<>();
            for (CompiledFlow.Node node : flow.nodes()) {
                contexts.put(node.id(), context(node, flow.definition(), request, parameters,
                        secretsUsed, log));
            }
            for (CompiledFlow.Node node : flow.nodes()) {
                if (node.source()) {
                    invoke(run, node, contexts.get(node.id()), request, log);
                }
            }
            for (CompiledFlow.Node node = next(flow, run); node != null; node = next(flow, run)) {
                invoke(run, node, contexts.get(node.id()), request, log);
            }
            status = Status.SUCCEEDED;
        } catch (RunFailure f) {
            status = RunResult.CANCELLED.equals(f.code) ? Status.CANCELLED : Status.FAILED;
            code = f.code;
            error = f.getMessage();
            failedProcessor = f.processor;
            log.add(new LogLine(clock.instant(), f.processor, "ERROR", f.getMessage()));
        } finally {
            run.releaseAll();
            if (run.used != 0) {
                // The engine lost track of some content; say so on the run rather than hide it.
                log.add(new LogLine(clock.instant(), null, "ERROR", run.used
                        + " bytes were not given back to the run's memory cap"));
            }
        }
        return new RunResult(status, code, error, failedProcessor, parameters, secretsUsed, versions,
                List.copyOf(log), run.records);
    }

    /** The furthest-downstream processor with items waiting, or null when every queue is empty. */
    private static CompiledFlow.Node next(CompiledFlow flow, Run run) {
        List<CompiledFlow.Node> nodes = flow.nodes();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            if (!run.queue(nodes.get(i).id()).isEmpty()) {
                return nodes.get(i);
            }
        }
        return null;
    }

    private void invoke(Run run, CompiledFlow.Node node, NodeContext context, RunRequest request,
                        List<LogLine> log) {
        boolean hadItems = !run.queue(node.id()).isEmpty();
        for (int attempt = 1; ; attempt++) {
            if (request.cancelled().getAsBoolean()) {
                throw new RunFailure(RunResult.CANCELLED, node.id(), "The run was stopped.");
            }
            RunSession session = new RunSession(run, node);
            long start = clock.millis();
            try {
                node.processor().onTrigger(context, session);
                session.checkAccounted();
            } catch (RuntimeException e) {
                session.rollback();
                if (causedBy(e, Run.OverCap.class)) {
                    throw new RunFailure(RunResult.MEMORY_LIMIT, node.id(), "\"" + node.id()
                            + "\" went over the run's memory cap of "
                            + request.memoryBytes() / (1024 * 1024) + " MB.");
                }
                if (request.cancelled().getAsBoolean()) {
                    throw new RunFailure(RunResult.CANCELLED, node.id(), "The run was stopped.");
                }
                if (attempt >= node.maxAttempts()) {
                    throw new RunFailure(RunResult.PROCESSOR_FAILED, node.id(), "\"" + node.id()
                            + "\" failed" + (node.maxAttempts() > 1 ? " after " + attempt
                            + " attempts" : "") + ": " + message(e));
                }
                log.add(new LogLine(clock.instant(), node.id(), "WARN", "Attempt " + attempt
                        + " failed, retrying: " + message(e)));
                pause(node);
                continue;
            }
            String failure = session.commit(clock.millis() - start);
            if (failure != null) {
                throw new RunFailure(RunResult.ITEM_FAILED, node.id(), failure);
            }
            if (!node.source() && hadItems && !session.tookAny()) {
                throw new RunFailure(RunResult.NO_PROGRESS, node.id(), "\"" + node.id()
                        + "\" was invoked with items waiting and took none.");
            }
            return;
        }
    }

    private void pause(CompiledFlow.Node node) {
        if (node.backoff().isZero()) {
            return;
        }
        try {
            sleeper.sleep(node.backoff());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RunFailure(RunResult.CANCELLED, node.id(), "The run was interrupted.");
        }
    }

    /** The flow's parameters for this run: overrides where given, defaults otherwise, typed. */
    private static Map<String, Object> parameters(FlowDefinition definition,
                                                  Map<String, Object> overrides) {
        for (String name : overrides.keySet()) {
            if (!definition.parameters().containsKey(name)) {
                throw new RunFailure(RunResult.INVALID_PARAMETERS, null,
                        "The flow has no parameter \"" + name + "\".");
            }
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        definition.parameters().forEach((name, parameter) -> {
            PropertyType type = FlowValidator.parameterType(parameter.type());
            Object raw = overrides.containsKey(name) ? overrides.get(name)
                    : parameter.defaultValue() == null ? null
                    : ConfiguredValue.fromJson(parameter.defaultValue());
            if (raw == null) {
                throw new RunFailure(RunResult.INVALID_PARAMETERS, null, "Parameter \"" + name
                        + "\" has no default and was not given.");
            }
            try {
                resolved.put(name, FlowValidator.typed(type, raw));
            } catch (IllegalArgumentException e) {
                throw new RunFailure(RunResult.INVALID_PARAMETERS, null, "Parameter \"" + name
                        + "\" " + e.getMessage() + ".");
            }
        });
        return Collections.unmodifiableMap(resolved);
    }

    /** A processor's context, with its properties' run-level references resolved once. */
    private NodeContext context(CompiledFlow.Node node, FlowDefinition definition, RunRequest request,
                                Map<String, Object> parameters, Set<String> secretsUsed,
                                List<LogLine> log) {
        TimeWindow window = request.window();
        Function<Reference, Object> runLevel = r -> switch (r.scope()) {
            case PARAMETERS -> parameters.get(r.key());
            case RUN -> {
                if (window == null) {
                    throw new RunFailure(RunResult.INVALID_PARAMETERS, node.id(), "${" + r.name()
                            + "} needs a run with a window, and this run has none.");
                }
                yield r.key().equals("window.start") ? window.start() : window.end();
            }
            case SECRETS -> {
                String value = request.secrets().apply(r.key());
                if (value == null) {
                    throw new RunFailure(RunResult.MISSING_SECRET, node.id(), "The organization "
                            + "has no flow secret called \"" + r.key() + "\".");
                }
                secretsUsed.add(r.key());
                yield value;
            }
            case ITEM, ATTRIBUTE -> null;
        };
        Map<String, ConfiguredValue> properties = new LinkedHashMap<>();
        node.properties().forEach((name, v) -> properties.put(name, v.resolve(runLevel, ITEM_LEVEL)));
        Map<String, PropertyValue> dynamic = new LinkedHashMap<>();
        node.dynamicProperties().forEach((name, v) -> dynamic.put(name, v.resolve(runLevel, ITEM_LEVEL)));
        FlowDefinition.Trigger trigger = definition.trigger();
        EventMatch triggerMatch = trigger == null || !"events".equals(trigger.type()) ? null
                : trigger.match() != null ? trigger.match() : new EventMatch(null, null, null, null, null, null, null);
        return new NodeContext(node.id(), definition.externalId(), properties, Collections.unmodifiableMap(dynamic),
                window, parameters, request, triggerMatch, secretsUsed, log, clock);
    }

    /** What an item's references resolve to: its attributes, and a summary of its series. */
    private static final Function<Item, Function<Reference, Object>> ITEM_LEVEL = item -> r -> {
        if (r.scope() == Scope.ITEM) {
            return summary(item, r.key());
        }
        String value = item.attribute(r.key());
        if (value == null) {
            throw new ProcessException("${" + r.name() + "}: " + item + " has no attribute \""
                    + r.key() + "\"");
        }
        return value;
    };

    /** {@code ${item.<key>}} for a timeseries item. */
    private static Object summary(Item item, String key) {
        TimeseriesContent ts = item.content(TimeseriesContent.class);
        int n = ts.rowCount();
        if (key.equals("count")) {
            return BigDecimal.valueOf(n);
        }
        if (n == 0) {
            throw new ProcessException("${item." + key + "}: " + item + " has no points");
        }
        int last = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            if (ts.timestamp(i) >= ts.timestamp(last)) {
                last = i;
            }
            double v = ts.value(i);
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
        }
        return switch (key) {
            case "last" -> ts.value(last);
            case "lastTime" -> Instant.ofEpochMilli(ts.timestamp(last));
            case "min" -> min;
            case "max" -> max;
            case "mean" -> sum / n;
            default -> throw new ProcessException("${item." + key + "} is not an item summary");
        };
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static String message(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** Ends the run: why, and which processor, when one did it. */
    private static final class RunFailure extends RuntimeException {
        final String code;
        final String processor;

        RunFailure(String code, String processor, String message) {
            super(message, null, false, false);
            this.code = code;
            this.processor = processor;
        }
    }

    private static final class NodeContext implements ProcessContext {
        private final String processorId;
        private final String flowExternalId;
        private final Map<String, ConfiguredValue> properties;
        private final Map<String, PropertyValue> dynamic;
        private final TimeWindow window;
        private final Map<String, Object> parameters;
        private final RunRequest request;
        private final EventMatch triggerMatch;
        private final Set<String> secretsUsed;
        private final RunLog runLog;

        NodeContext(String processorId, String flowExternalId, Map<String, ConfiguredValue> properties,
                    Map<String, PropertyValue> dynamic, TimeWindow window,
                    Map<String, Object> parameters, RunRequest request, EventMatch triggerMatch,
                    Set<String> secretsUsed, List<LogLine> log, Clock clock) {
            this.processorId = processorId;
            this.flowExternalId = flowExternalId;
            this.properties = properties;
            this.dynamic = dynamic;
            this.window = window;
            this.parameters = parameters;
            this.request = request;
            this.triggerMatch = triggerMatch;
            this.secretsUsed = secretsUsed;
            this.runLog = new RunLog() {
                @Override
                public void info(String message) {
                    log.add(new LogLine(clock.instant(), processorId, "INFO", message));
                }

                @Override
                public void warn(String message) {
                    log.add(new LogLine(clock.instant(), processorId, "WARN", message));
                }
            };
        }

        @Override
        public PropertyValue property(String name) {
            ConfiguredValue v = properties.get(name);
            return v != null ? v : ConfiguredValue.of(name, null);
        }

        @Override
        public Map<String, PropertyValue> dynamicProperties() {
            return dynamic;
        }

        @Override
        public String processorId() {
            return processorId;
        }

        @Override
        public Optional<TimeWindow> window() {
            return Optional.ofNullable(window);
        }

        @Override
        public Map<String, Object> parameters() {
            return parameters;
        }

        @Override
        public String secret(String name) {
            String value = request.secrets().apply(name);
            if (value == null) {
                throw new ProcessException("The organization has no flow secret called \"" + name + "\"");
            }
            secretsUsed.add(name);
            return value;
        }

        @Override
        public Long dataSetId() {
            return request.dataSetId();
        }

        @Override
        public Optional<EventMatch> triggerMatch() {
            return Optional.ofNullable(triggerMatch);
        }

        @Override
        public DatahubClient datahub() {
            if (request.datahub() == null) {
                throw new ProcessException("This run has no DataHub client");
            }
            return request.datahub();
        }

        @Override
        public boolean isPreview() {
            return request.preview();
        }

        @Override
        public boolean isCancelled() {
            return request.cancelled().getAsBoolean();
        }

        @Override
        public RunLog log() {
            return runLog;
        }

        @Override
        public UUID runId() {
            return request.runId();
        }

        @Override
        public int attempt() {
            return request.attempt();
        }

        @Override
        public String flowExternalId() {
            return flowExternalId;
        }

        @Override
        public String accessToken() {
            if (request.accessToken() == null) {
                throw new ProcessException("This run has no access token");
            }
            return request.accessToken().get();
        }
    }
}
