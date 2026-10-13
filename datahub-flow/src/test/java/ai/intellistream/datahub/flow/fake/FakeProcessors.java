// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.fake;

import ai.intellistream.datahub.flow.api.DynamicPropertyDescriptor;
import ai.intellistream.datahub.flow.api.EventsContent;
import ai.intellistream.datahub.flow.api.EventsContent.EventRow;
import ai.intellistream.datahub.flow.api.Item;
import ai.intellistream.datahub.flow.api.ProcessContext;
import ai.intellistream.datahub.flow.api.ProcessException;
import ai.intellistream.datahub.flow.api.ProcessSession;
import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.api.ProcessorDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor;
import ai.intellistream.datahub.flow.api.PropertyDescriptor.AllowableValue;
import ai.intellistream.datahub.flow.api.PropertyType;
import ai.intellistream.datahub.flow.api.PropertyValues;
import ai.intellistream.datahub.flow.api.Relationship;
import ai.intellistream.datahub.flow.api.TimeseriesContent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processors for testing the engine: small, predictable, and shaped like the built-in ones the
 * plan's example flow uses, plus some that misbehave on purpose.
 */
public final class FakeProcessors {

    private FakeProcessors() {
    }

    /** All the well-behaved ones, for the validator. */
    public static List<Processor> catalog() {
        return List.of(new Source(), new Scale(), new Sink(), new Threshold(), new EventsSink(),
                new Secretive(), new Range());
    }

    /**
     * {@code test.source}: one timeseries item per series in {@code series}, {@code points} points
     * a minute apart from {@code from}, valued 1, 2, 3…
     */
    public static final class Source implements Processor {
        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.source", "1.0.0")
                    .property(PropertyDescriptor.builder("series", PropertyType.LIST).required().references().build())
                    .property(PropertyDescriptor.builder("from", PropertyType.INSTANT).references()
                            .defaultValue("${run.window.start}").build())
                    .property(PropertyDescriptor.builder("points", PropertyType.NUMBER).defaultValue(3).build())
                    .relationship(Relationship.of("success", "Each series read"))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            Instant from = context.property("from").asInstant();
            int points = context.property("points").asDouble().intValue();
            for (String series : context.property("series").asList()) {
                Item item = session.create();
                item = session.putAllAttributes(item, Map.of("timeseries.externalId", series,
                        "window.start", from.toString()));
                TimeseriesContent.Builder b = session.newTimeseries(points);
                for (int i = 0; i < points; i++) {
                    b.add(from.toEpochMilli() + i * 60_000L, i + 1);
                }
                item = session.write(item, b.build());
                session.receive(item, "datahub://timeseries/" + series + "?from=" + from);
                session.transfer(item, "success");
            }
        }
    }

    /** {@code test.scale}: value × factor + offset. */
    public static final class Scale implements Processor {
        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.scale", "1.0.0")
                    .property(PropertyDescriptor.builder("factor", PropertyType.NUMBER).references().defaultValue(1).build())
                    .property(PropertyDescriptor.builder("offset", PropertyType.NUMBER).references().defaultValue(0).build())
                    .relationship(Relationship.of("success", "Scaled"))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            Item item = session.get();
            double factor = context.property("factor").evaluate(item).asDouble();
            double offset = context.property("offset").evaluate(item).asDouble();
            TimeseriesContent in = item.content(TimeseriesContent.class);
            TimeseriesContent.Builder b = session.newTimeseries(in.rowCount());
            for (int i = 0; i < in.rowCount(); i++) {
                b.add(in.timestamp(i), in.value(i) * factor + offset);
            }
            item = session.write(item, b.build());
            session.transfer(item, "success");
        }
    }

    /**
     * {@code test.sink}: "writes" each item to {@code target}, noting it in {@link #written}. A
     * target in {@code failing} goes to {@code failure} instead.
     */
    public static final class Sink implements Processor {
        public final List<String> written = Collections.synchronizedList(new ArrayList<>());
        public final List<String> failing = new ArrayList<>();

        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.sink", "2.1.0")
                    .property(PropertyDescriptor.builder("target", PropertyType.STRING).required().references().build())
                    .relationship(new Relationship("success", "Written", Relationship.Unconnected.TERMINATE))
                    .relationship(new Relationship("failure", "Not written", Relationship.Unconnected.FAIL_RUN))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            Item item = session.get();
            String target = context.property("target").evaluate(item).asString();
            if (failing.contains(target)) {
                item = session.putAttribute(item, "failure.reason", "403 on " + target);
                session.transfer(item, "failure");
                return;
            }
            int points = item.content(TimeseriesContent.class).rowCount();
            if (!context.isPreview()) {
                written.add(target + ":" + points);
            }
            session.send(item, "datahub://timeseries/" + target);
            session.transfer(item, "success");
        }
    }

    /** {@code test.threshold}: an {@code active} event row per point above {@code threshold}. */
    public static final class Threshold implements Processor {
        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.threshold", "1.0.0")
                    .property(PropertyDescriptor.builder("threshold", PropertyType.NUMBER).required().references().build())
                    .property(PropertyDescriptor.builder("direction", PropertyType.ENUM).defaultValue("above")
                            .allowableValues(new AllowableValue("above", "Above"), new AllowableValue("below", "Below"))
                            .build())
                    .relationship(Relationship.of("alarms", "Periods over the threshold"))
                    .relationship(Relationship.of("success", "The input, unchanged"))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            Item item = session.get();
            double threshold = context.property("threshold").asDouble();
            TimeseriesContent ts = item.content(TimeseriesContent.class);
            List<EventRow> rows = new ArrayList<>();
            for (int i = 0; i < ts.rowCount(); i++) {
                if (ts.value(i) > threshold) {
                    rows.add(new EventRow(item.attribute("timeseries.externalId") + ":" + i,
                            Instant.ofEpochMilli(ts.timestamp(i)), "active", Map.of()));
                }
            }
            if (!rows.isEmpty()) {
                Item alarms = session.create(item);
                alarms = session.write(alarms, EventsContent.of(rows));
                session.transfer(alarms, "alarms");
            }
            session.transfer(item, "success");
        }
    }

    /** {@code test.events.sink}: takes events items; dynamic properties are metadata keys. */
    public static final class EventsSink implements Processor {
        public final List<Integer> rows = Collections.synchronizedList(new ArrayList<>());

        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.events.sink", "1.0.0")
                    .property(PropertyDescriptor.builder("type", PropertyType.STRING).required().references().build())
                    .dynamicProperties(new DynamicPropertyDescriptor("A metadata key", "Its value",
                            PropertyType.STRING, true))
                    .relationship(new Relationship("success", "Created", Relationship.Unconnected.TERMINATE))
                    .relationship(new Relationship("failure", "Not created", Relationship.Unconnected.FAIL_RUN))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            Item item = session.get();
            rows.add(item.content(EventsContent.class).rows().size());
            session.send(item, "datahub://events?type=" + context.property("type").asString());
            session.transfer(item, "success");
        }
    }

    /** {@code test.secret}: has a secret property, and reads it. */
    public static final class Secretive implements Processor {
        public final List<String> seen = new ArrayList<>();

        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.secret", "1.0.0")
                    .property(PropertyDescriptor.builder("password", PropertyType.SECRET).required().build())
                    .property(PropertyDescriptor.builder("note", PropertyType.STRING).references().build())
                    .relationship(Relationship.of("success", "Passed through"))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            seen.add(context.property("password").asString());
            session.transfer(session.get(), "success");
        }
    }

    /** {@code test.range}: needs {@code min}, {@code max} or both — a combination check. */
    public static final class Range implements Processor {
        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.range", "1.0.0")
                    .property(PropertyDescriptor.builder("min", PropertyType.NUMBER).references().build())
                    .property(PropertyDescriptor.builder("max", PropertyType.NUMBER).references().build())
                    .relationship(Relationship.of("success", "In range"))
                    .build();
        }

        @Override
        public List<String> validate(PropertyValues properties) {
            if (!properties.property("min").isSet() && !properties.property("max").isSet()) {
                return List.of("Set min, max or both.");
            }
            return List.of();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            session.transfer(session.get(), "success");
        }
    }

    /**
     * {@code test.flaky}: throws on its first {@code failures} invocations — after taking an item
     * and allocating content, so a rollback has something to undo — then passes items through.
     */
    public static final class Flaky implements Processor {
        public final AtomicInteger calls = new AtomicInteger();
        private final int failures;

        public Flaky(int failures) {
            this.failures = failures;
        }

        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.flaky", "1.0.0")
                    .relationship(Relationship.of("success", "Passed through"))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            Item item = session.get();
            item = session.putAttribute(item, "flaky", "touched");
            TimeseriesContent.Builder b = session.newTimeseries(10);
            b.add(0, 0);
            Item replaced = session.write(item, b.build());
            if (calls.incrementAndGet() <= failures) {
                throw new ProcessException("Simulated failure " + calls.get());
            }
            session.transfer(replaced, "success");
        }
    }

    /** {@code test.lazy}: never takes an item. */
    public static final class Lazy implements Processor {
        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.lazy", "1.0.0")
                    .relationship(Relationship.of("success", "Never"))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
        }
    }

    /** {@code test.forgetful}: takes an item and forgets to send it on. */
    public static final class Forgetful implements Processor {
        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.forgetful", "1.0.0")
                    .relationship(Relationship.of("success", "Never"))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            session.get();
        }
    }

    /** {@code test.hog}: fills a series until the run's memory runs out. */
    public static final class Hog implements Processor {
        @Override
        public ProcessorDescriptor describe() {
            return ProcessorDescriptor.builder("test.hog", "1.0.0")
                    .relationship(Relationship.of("success", "Never"))
                    .build();
        }

        @Override
        public void onTrigger(ProcessContext context, ProcessSession session) {
            Item item = session.get();
            TimeseriesContent.Builder b = session.newTimeseries(1024);
            for (long i = 0; ; i++) {
                b.add(i, i);
            }
        }
    }
}
