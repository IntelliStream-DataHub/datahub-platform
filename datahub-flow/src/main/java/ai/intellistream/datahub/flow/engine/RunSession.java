// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.engine;

import ai.intellistream.datahub.flow.api.Content;
import ai.intellistream.datahub.flow.api.EventsContent;
import ai.intellistream.datahub.flow.api.Item;
import ai.intellistream.datahub.flow.api.ProcessException;
import ai.intellistream.datahub.flow.api.ProcessSession;
import ai.intellistream.datahub.flow.api.Relationship;
import ai.intellistream.datahub.flow.api.TimeseriesContent;
import ai.intellistream.datahub.flow.engine.FlowRecord.Action;
import ai.intellistream.datahub.helpers.utils.IdGenerator;
import org.apache.arrow.memory.AllocationReservation;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.vector.VectorSchemaRoot;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One invocation of one processor: the transaction its session calls make up.
 *
 * <p>Nothing outside the session changes until {@link #commit}: items taken from the queue are
 * held here, new content is tracked here, and records wait here. Commit sends every item on, shares
 * or releases content, and emits the records; {@link #rollback} puts the taken items back at the
 * front of the queue and releases whatever the invocation allocated, as if it had never run.
 */
final class RunSession implements ProcessSession {

    private static final String REMOVED = "\u0000removed";

    private final Run run;
    private final CompiledFlow.Node node;
    private final ArrayDeque<RunItem> queue;

    /** As taken from the queue, in order, to put back on a rollback. */
    private final List<RunItem> taken = new ArrayList<>();
    /** Every item the invocation holds, taken or created, by id: the current version. */
    private final Map<UUID, RunItem> current = new LinkedHashMap<>();
    /** Item id → the relationship it was transferred to, or {@link #REMOVED}. */
    private final Map<UUID, String> disposition = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    /** Batches handed out by {@link #newTimeseries}, built or not. */
    private final List<VectorSchemaRoot> newRoots = new ArrayList<>();
    /** Content first seen in this invocation; it has no holders until commit. */
    private final Map<Content, ContentRef> newRefs = new IdentityHashMap<>();

    RunSession(Run run, CompiledFlow.Node node) {
        this.run = run;
        this.node = node;
        this.queue = run.queue(node.id());
    }

    /** Whether the invocation took anything from its queue. */
    boolean tookAny() {
        return !taken.isEmpty();
    }

    @Override
    public Item get() {
        RunItem item = queue.poll();
        if (item != null) {
            taken.add(item);
            current.put(item.id(), item);
        }
        return item;
    }

    @Override
    public List<Item> get(int max) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            Item item = get();
            if (item == null) {
                break;
            }
            items.add(item);
        }
        return items;
    }

    @Override
    public Item create() {
        RunItem item = new RunItem(IdGenerator.getRandomUUID7(), Map.of(), null, List.of());
        current.put(item.id(), item);
        pending.add(new Pending(Action.CREATE, item, null, Map.of(), null));
        return item;
    }

    @Override
    public Item create(Item parent) {
        RunItem from = live(parent);
        RunItem item = new RunItem(IdGenerator.getRandomUUID7(), from.attributes(), null,
                List.of(from.id()));
        current.put(item.id(), item);
        pending.add(new Pending(Action.FORK, item, null, item.attributes(), null));
        return item;
    }

    @Override
    public Item putAttribute(Item item, String key, String value) {
        return putAllAttributes(item, Map.of(key, value));
    }

    @Override
    public Item putAllAttributes(Item item, Map<String, String> attributes) {
        RunItem from = live(item);
        Map<String, String> changed = new LinkedHashMap<>(from.attributes());
        changed.putAll(attributes);
        return attributesChanged(from.withAttributes(changed), attributes);
    }

    @Override
    public Item removeAttribute(Item item, String key) {
        RunItem from = live(item);
        if (!from.attributes().containsKey(key)) {
            return from;
        }
        Map<String, String> changed = new LinkedHashMap<>(from.attributes());
        changed.remove(key);
        return attributesChanged(from.withAttributes(changed), Map.of(key, ""));
    }

    /** Records an attribute change, folded into the previous one when it was on the same item. */
    private RunItem attributesChanged(RunItem item, Map<String, String> changes) {
        current.put(item.id(), item);
        Pending last = pending.isEmpty() ? null : pending.getLast();
        if (last != null && last.item().id().equals(item.id())
                && (last.action() == Action.ATTRIBUTES_MODIFIED || last.action() == Action.CREATE
                || last.action() == Action.FORK)) {
            Map<String, String> merged = new LinkedHashMap<>(last.changed());
            merged.putAll(changes);
            pending.set(pending.size() - 1, new Pending(last.action(), item, null, merged, null));
        } else {
            pending.add(new Pending(Action.ATTRIBUTES_MODIFIED, item, null, changes, null));
        }
        return item;
    }

    @Override
    public Item write(Item item, Content content) {
        RunItem from = live(item);
        RunItem written = from.withContent(adopt(content));
        current.put(written.id(), written);
        pending.add(new Pending(Action.CONTENT_MODIFIED, written, null, Map.of(), null));
        return written;
    }

    /** The engine's hold on {@code content}, taking it over if this is the first time it is seen. */
    private ContentRef adopt(Content content) {
        ContentRef ref = run.refs.get(content);
        if (ref == null) {
            ref = newRefs.get(content);
        }
        if (ref != null) {
            return ref;
        }
        ref = switch (content) {
            case TimeseriesContent ts -> {
                if (!newRoots.contains(ts.root())) {
                    throw new ProcessException("This timeseries content was not made with this "
                            + "invocation's session (newTimeseries)");
                }
                yield new ContentRef(ts, ts.root());
            }
            case EventsContent events -> new ContentRef(events, reserve(events.size()));
        };
        newRefs.put(content, ref);
        return ref;
    }

    /** Counts heap content against the run's cap, so it fails like Arrow memory does. */
    private AllocationReservation reserve(long bytes) {
        AllocationReservation reservation = run.allocator.newReservation();
        if (bytes > 0 && !reservation.add(bytes)) {
            reservation.close();
            throw new OutOfMemoryException("Content of " + bytes + " bytes does not fit in the "
                    + "run's memory cap");
        }
        return reservation;
    }

    @Override
    public TimeseriesContent.Builder newTimeseries(int expectedRows) {
        VectorSchemaRoot root = TimeseriesContent.newRoot(run.allocator);
        newRoots.add(root);
        return new TimeseriesContent.Builder(root, expectedRows);
    }

    @Override
    public void receive(Item item, String transitUri) {
        pending.add(new Pending(Action.RECEIVE, live(item), null, Map.of(), transitUri));
    }

    @Override
    public void send(Item item, String transitUri) {
        pending.add(new Pending(Action.SEND, live(item), null, Map.of(), transitUri));
    }

    @Override
    public void transfer(Item item, String relationship) {
        RunItem from = live(item);
        if (!node.outcomes().containsKey(relationship)) {
            throw new ProcessException("\"" + node.id() + "\" has no relationship \""
                    + relationship + "\"");
        }
        disposition.put(from.id(), relationship);
    }

    @Override
    public void remove(Item item) {
        disposition.put(live(item).id(), REMOVED);
    }

    /** The current version of an item this invocation holds and has not yet sent on. */
    private RunItem live(Item item) {
        RunItem held = item == null ? null : current.get(item.id());
        if (held == null) {
            throw new IllegalStateException(item + " is not held by \"" + node.id()
                    + "\": take it with get() or make it with create() first");
        }
        if (held != item) {
            throw new IllegalStateException(item + " is an old version: use the item the last "
                    + "session call returned");
        }
        if (disposition.containsKey(held.id())) {
            throw new IllegalStateException(item + " was already transferred or removed");
        }
        return held;
    }

    /**
     * Checks that every item the invocation holds was transferred or removed, before anything is
     * committed.
     *
     * @throws ProcessException for one that was neither; the caller rolls back
     */
    void checkAccounted() {
        for (RunItem item : current.values()) {
            if (!disposition.containsKey(item.id())) {
                throw new ProcessException(item + " was neither transferred nor removed by \""
                        + node.id() + "\"");
            }
        }
    }

    /**
     * Sends every item on, shares or releases content, and emits the records. Called only after
     * {@link #checkAccounted()}.
     *
     * @return why the run must fail — an item sent to a relationship that fails the run when left
     *         unconnected — or null
     */
    String commit(long durationMillis) {
        Instant now = run.clock.instant();
        List<FlowRecord> records = new ArrayList<>();
        for (Pending p : pending) {
            records.add(record(now, durationMillis, p.action(), p.item(), p.item().parents(),
                    p.relationship(), p.changed(), p.uri()));
        }

        String failure = null;
        List<Placement> placements = new ArrayList<>();
        for (RunItem item : current.values()) {
            String relationship = disposition.get(item.id());
            if (REMOVED.equals(relationship)) {
                records.add(record(now, durationMillis, Action.DROP, item, List.of(), null, Map.of(), null));
                continue;
            }
            records.add(record(now, durationMillis, Action.ROUTE, item, List.of(), relationship,
                    Map.of(), null));
            CompiledFlow.Outcome outcome = node.outcomes().get(relationship);
            List<String> targets = outcome.targets();
            if (targets.size() == 1) {
                placements.add(new Placement(targets.getFirst(), item));
            } else if (targets.size() > 1) {
                for (String target : targets) {
                    RunItem copy = new RunItem(IdGenerator.getRandomUUID7(), item.attributes(),
                            item.ref(), List.of(item.id()));
                    records.add(record(now, durationMillis, Action.CLONE, copy, copy.parents(),
                            relationship, Map.of(), null));
                    placements.add(new Placement(target, copy));
                }
            } else if (outcome.terminated()
                    || outcome.whenUnconnected() == Relationship.Unconnected.TERMINATE) {
                records.add(record(now, durationMillis, Action.DROP, item, List.of(), relationship,
                        Map.of(), null));
            } else if (failure == null) {
                String reason = item.attribute("failure.reason");
                failure = "\"" + node.id() + "\" sent " + item + " to \"" + relationship
                        + "\", which is not connected" + (reason == null ? "" : ": " + reason);
            }
        }

        // Share before releasing, so content passed from a taken item to its successor never
        // drops to no holders in between.
        for (Placement p : placements) {
            run.queue(p.target()).add(p.item());
            hold(p.item().ref());
        }
        for (RunItem item : taken) {
            if (item.ref() != null) {
                release(item.ref());
            }
        }
        for (ContentRef ref : newRefs.values()) {
            if (ref.holders == 0) {
                ref.close();
            }
        }
        for (VectorSchemaRoot root : newRoots) {
            if (!run.refs.containsKey(contentOf(root))) {
                root.close();
            }
        }
        for (FlowRecord record : records) {
            run.emit(record);
        }
        return failure;
    }

    /** Puts the taken items back and releases what the invocation allocated. */
    void rollback() {
        for (int i = taken.size() - 1; i >= 0; i--) {
            queue.addFirst(taken.get(i));
        }
        for (ContentRef ref : newRefs.values()) {
            ref.close();
        }
        for (VectorSchemaRoot root : newRoots) {
            root.close();
        }
    }

    private void hold(ContentRef ref) {
        if (ref == null) {
            return;
        }
        if (ref.holders++ == 0) {
            run.refs.put(ref.content, ref);
        }
    }

    private void release(ContentRef ref) {
        if (--ref.holders == 0) {
            run.refs.remove(ref.content);
            ref.close();
        }
    }

    /** The content in {@link Run#refs} whose batch is {@code root}, or null. */
    private Content contentOf(VectorSchemaRoot root) {
        for (ContentRef ref : newRefs.values()) {
            if (ref.content instanceof TimeseriesContent ts && ts.root() == root) {
                return ref.content;
            }
        }
        return null;
    }

    private FlowRecord record(Instant time, long durationMillis, Action action, RunItem item,
                              List<UUID> parents, String relationship, Map<String, String> changed,
                              String uri) {
        ContentRef ref = item.ref();
        return new FlowRecord(time, run.runId, run.attempt, node.id(), node.type(), action, item.id(),
                parents, relationship, ref == null ? null : ref.content.kind(),
                ref == null ? 0 : ref.content.size(), ref == null ? 0 : ref.hash(),
                Collections.unmodifiableMap(new LinkedHashMap<>(changed)), uri, durationMillis);
    }

    private record Pending(Action action, RunItem item, String relationship,
                           Map<String, String> changed, String uri) {
    }

    private record Placement(String target, RunItem item) {
    }
}
