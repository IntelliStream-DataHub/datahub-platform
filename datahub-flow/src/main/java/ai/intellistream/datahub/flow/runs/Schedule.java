// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import ai.intellistream.datahub.flow.api.EventMatch;
import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.flow.definition.FlowDefinition;
import org.springframework.scheduling.support.CronExpression;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deployment's trigger as it is run: what the definition's {@code trigger} says, with a window
 * given as {@code ${parameters.<name>}} resolved to that parameter's default when the flow is
 * deployed. Stored on the flow row as JSON ({@link #toJson}), so the sweep never re-reads the
 * definition.
 *
 * <p>The tick at T covers the time since the tick before it, {@code [previous tick, T)}, so every
 * point is covered once however irregular the cron expression; with a window set, it covers
 * {@code [T - window, T)} instead. It is due at {@code T + delay}. Ticks come from Spring's
 * six-field cron expression read in {@link #zone}, so a daylight-saving change moves them the way
 * a wall clock in that zone would.
 *
 * <p>An {@code events} trigger has no cron expression: its tick is set by a matching event
 * ({@code EventWakes}), {@link #debounce} after the first one, so a burst is one run; the run covers the
 * time since the tick before it, and starts {@link #delay} after the tick, so the events the stateless
 * consumer is still inserting have landed. {@link #match} is the trigger's own filter: the event is a
 * signal, and whether the run reads it is up to the flow (a {@code trigger-events} step).
 *
 * @param window   null for a schedule that covers the time since its previous tick, and for a manual
 *                 trigger without one; for an {@code events} trigger, the window of a manual run
 * @param debounce for an {@code events} trigger, how long after the first matching event its tick is;
 *                 null otherwise
 * @param match    for an {@code events} trigger, the events that wake it; null otherwise
 */
public record Schedule(String type, String cron, ZoneId zone, Duration window, Duration delay,
                       Duration debounce, EventMatch match) {

    public static final String MANUAL = "manual";
    public static final String SCHEDULE = "schedule";
    public static final String EVENTS = "events";
    public static final Duration DEFAULT_DEBOUNCE = Duration.ofSeconds(10);
    /** How long after its tick an {@code events} run starts by default: the stateless consumer's insert lag. */
    public static final Duration DEFAULT_EVENTS_DELAY = Duration.ofSeconds(10);
    /** An events trigger with no filter: every event wakes it. */
    private static final EventMatch NO_FILTER = new EventMatch(null, null, null, null, null, null, null);

    private static final Pattern PARAMETER = Pattern.compile("^\\$\\{parameters\\.([A-Za-z0-9_]+)}$");
    /** How far back {@link #latestDueTick} looks for the last tick, in windows. */
    private static final int LOOKBACK_WINDOWS = 2;
    private static final int MAX_STEPS = 10_000;
    /** How far back {@link #previousTick} looks. */
    private static final Duration MAX_GAP = Duration.ofDays(366 * 10);

    public Schedule {
        delay = delay == null ? Duration.ZERO : delay;
        zone = zone == null ? ZoneOffset.UTC : zone;
    }

    public Schedule(String type, String cron, ZoneId zone, Duration window, Duration delay) {
        this(type, cron, zone, window, delay, null, null);
    }

    public boolean scheduled() {
        return SCHEDULE.equals(type);
    }

    /** Whether events set its ticks: an {@code events} trigger. */
    public boolean evented() {
        return EVENTS.equals(type);
    }

    /** The trigger of a validated definition; a definition without one is manual, without a window. */
    public static Schedule of(FlowDefinition definition) {
        FlowDefinition.Trigger trigger = definition.trigger();
        if (trigger == null) {
            return new Schedule(MANUAL, null, null, null, null);
        }
        if (EVENTS.equals(trigger.type())) {
            return new Schedule(EVENTS, null, null,
                    trigger.window() == null ? null : resolveWindow(trigger.window(), definition),
                    trigger.delay() == null ? DEFAULT_EVENTS_DELAY : Duration.parse(trigger.delay()),
                    trigger.debounce() == null ? DEFAULT_DEBOUNCE : Duration.parse(trigger.debounce()),
                    trigger.match() == null ? NO_FILTER : trigger.match());
        }
        Schedule schedule = new Schedule(trigger.type(), trigger.cron(),
                trigger.timezone() == null ? null : ZoneId.of(trigger.timezone()),
                trigger.window() == null ? null : resolveWindow(trigger.window(), definition),
                trigger.delay() == null ? null : Duration.parse(trigger.delay()));
        if (schedule.scheduled() && schedule.window() == null) {
            Instant next = schedule.nextTick(Instant.now());
            if (next == null || schedule.previousTick(next) == null) {
                throw new IllegalArgumentException("The cron expression's ticks are too far apart "
                        + "for each run to cover the time since the one before; set the trigger's window.");
            }
        }
        return schedule;
    }

    private static Duration resolveWindow(String window, FlowDefinition definition) {
        Matcher m = PARAMETER.matcher(window.trim());
        if (!m.matches()) {
            return Duration.parse(window);
        }
        FlowDefinition.Parameter parameter = definition.parameters().get(m.group(1));
        if (parameter == null || parameter.defaultValue() == null || parameter.defaultValue().isNull()) {
            throw new IllegalArgumentException("The trigger's window refers to parameter "
                    + m.group(1) + ", which has no default to deploy with.");
        }
        return Duration.parse(parameter.defaultValue().asString());
    }

    /** The first tick strictly after {@code after}, or null when the expression has no more. */
    public Instant nextTick(Instant after) {
        ZonedDateTime next = CronExpression.parse(cron).next(after.atZone(zone));
        return next == null ? null : next.toInstant();
    }

    /**
     * The latest tick strictly before {@code t}, or null when there is none within ten years.
     * Spring's cron expression only looks forward, so this searches for the latest second whose
     * next tick is still before {@code t}: ticks fall on whole seconds.
     */
    public Instant previousTick(Instant t) {
        Duration back = Duration.ofMinutes(1);
        long lo = t.minus(back).getEpochSecond();
        while (!tickBefore(lo, t)) {
            if (back.equals(MAX_GAP)) {
                return null;
            }
            back = back.multipliedBy(2);
            back = back.compareTo(MAX_GAP) > 0 ? MAX_GAP : back;
            lo = t.minus(back).getEpochSecond();
        }
        long hi = t.getEpochSecond() + 1;   // its next tick is after t
        while (hi - lo > 1) {
            long mid = lo + (hi - lo) / 2;
            if (tickBefore(mid, t)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return nextTick(Instant.ofEpochSecond(lo));
    }

    private boolean tickBefore(long second, Instant t) {
        Instant next = nextTick(Instant.ofEpochSecond(second));
        return next != null && next.isBefore(t);
    }

    public Instant dueAt(Instant tick) {
        return tick.plus(delay);
    }

    public TimeWindow windowOf(Instant tick) {
        if (window != null) {
            return new TimeWindow(tick.minus(window), tick);
        }
        Instant previous = previousTick(tick);
        if (previous == null) {
            throw new IllegalStateException("No tick of " + cron + " in the ten years before " + tick);
        }
        return new TimeWindow(previous, tick);
    }

    /**
     * The ticks from {@code firstTick} on that are due at {@code now}, oldest first and at most
     * {@code max}; and the tick after the last of them, where the schedule continues.
     */
    public Due due(Instant firstTick, Instant now, int max) {
        List<Instant> ticks = new ArrayList<>();
        Instant tick = firstTick;
        while (tick != null && ticks.size() < max && !dueAt(tick).isAfter(now)) {
            ticks.add(tick);
            tick = nextTick(tick);
        }
        return new Due(List.copyOf(ticks), tick);
    }

    /** @param next the tick to wait for next; null when the cron expression has none left */
    public record Due(List<Instant> ticks, Instant next) {
    }

    /**
     * The window a manual run takes when none is given: for a schedule, that of the latest tick that
     * is already due; for a manual or events trigger with a window, the window ending now (to the
     * second). Null when the trigger has no window.
     */
    public TimeWindow defaultWindow(Instant now) {
        if (scheduled()) {
            Instant tick = latestDueTick(now);
            if (tick != null) {
                return windowOf(tick);
            }
        }
        if (window == null) {
            return null;
        }
        Instant end = now.truncatedTo(ChronoUnit.SECONDS);
        return new TimeWindow(end.minus(window), end);
    }

    /**
     * The latest tick due at {@code now}: with a window, looking back a couple of windows, and null
     * if none is there.
     */
    Instant latestDueTick(Instant now) {
        Instant limit = now.minus(delay);
        if (window == null) {
            return previousTick(limit.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1));
        }
        Instant tick = nextTick(limit.minus(window.multipliedBy(LOOKBACK_WINDOWS)).minusSeconds(1));
        Instant latest = null;
        for (int i = 0; tick != null && !tick.isAfter(limit) && i < MAX_STEPS; i++) {
            latest = tick;
            tick = nextTick(tick);
        }
        return latest;
    }

    public String toJson(JsonMapper json) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        if (cron != null) {
            out.put("cron", cron);
            out.put("timezone", zone.getId());
        }
        if (window != null) {
            out.put("window", window.toString());
        }
        if (!delay.isZero()) {
            out.put("delay", delay.toString());
        }
        if (debounce != null) {
            out.put("debounce", debounce.toString());
        }
        if (match != null) {
            out.put("match", match);
        }
        return json.writeValueAsString(out);
    }

    public static Schedule fromJson(JsonMapper json, String text) {
        JsonNode node = json.readTree(text);
        return new Schedule(node.path("type").asString(),
                node.hasNonNull("cron") ? node.get("cron").asString() : null,
                node.hasNonNull("timezone") ? ZoneId.of(node.get("timezone").asString()) : null,
                node.hasNonNull("window") ? Duration.parse(node.get("window").asString()) : null,
                node.hasNonNull("delay") ? Duration.parse(node.get("delay").asString()) : null,
                node.hasNonNull("debounce") ? Duration.parse(node.get("debounce").asString()) : null,
                node.hasNonNull("match") ? json.treeToValue(node.get("match"), EventMatch.class) : null);
    }
}
