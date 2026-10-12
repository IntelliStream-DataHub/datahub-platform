// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

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
 * <p>The tick at T covers {@code [T - window, T)} and is due at {@code T + delay}. Ticks come from
 * Spring's six-field cron expression read in {@link #zone}, so a daylight-saving change moves them
 * the way a wall clock in that zone would.
 *
 * @param window null for a manual trigger without one
 */
public record Schedule(String type, String cron, ZoneId zone, Duration window, Duration delay) {

    public static final String MANUAL = "manual";
    public static final String SCHEDULE = "schedule";

    private static final Pattern PARAMETER = Pattern.compile("^\\$\\{parameters\\.([A-Za-z0-9_]+)}$");
    /** How far back {@link #latestDueTick} looks for the last tick, in windows. */
    private static final int LOOKBACK_WINDOWS = 2;
    private static final int MAX_STEPS = 10_000;

    public Schedule {
        delay = delay == null ? Duration.ZERO : delay;
        zone = zone == null ? ZoneOffset.UTC : zone;
    }

    public boolean scheduled() {
        return SCHEDULE.equals(type);
    }

    /** The trigger of a validated definition; a definition without one is manual, without a window. */
    public static Schedule of(FlowDefinition definition) {
        FlowDefinition.Trigger trigger = definition.trigger();
        if (trigger == null) {
            return new Schedule(MANUAL, null, null, null, null);
        }
        return new Schedule(trigger.type(), trigger.cron(),
                trigger.timezone() == null ? null : ZoneId.of(trigger.timezone()),
                trigger.window() == null ? null : resolveWindow(trigger.window(), definition),
                trigger.delay() == null ? null : Duration.parse(trigger.delay()));
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

    public Instant dueAt(Instant tick) {
        return tick.plus(delay);
    }

    public TimeWindow windowOf(Instant tick) {
        return new TimeWindow(tick.minus(window), tick);
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
     * is already due; for a manual trigger with a window, the window ending now (to the second).
     * Null when the trigger has no window.
     */
    public TimeWindow defaultWindow(Instant now) {
        if (window == null) {
            return null;
        }
        if (scheduled()) {
            Instant tick = latestDueTick(now);
            if (tick != null) {
                return windowOf(tick);
            }
        }
        Instant end = now.truncatedTo(ChronoUnit.SECONDS);
        return new TimeWindow(end.minus(window), end);
    }

    /** The latest tick due at {@code now}, looking back a couple of windows; null if none there. */
    Instant latestDueTick(Instant now) {
        Instant limit = now.minus(delay);
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
        return json.writeValueAsString(out);
    }

    public static Schedule fromJson(JsonMapper json, String text) {
        JsonNode node = json.readTree(text);
        return new Schedule(node.path("type").asString(),
                node.hasNonNull("cron") ? node.get("cron").asString() : null,
                node.hasNonNull("timezone") ? ZoneId.of(node.get("timezone").asString()) : null,
                node.hasNonNull("window") ? Duration.parse(node.get("window").asString()) : null,
                node.hasNonNull("delay") ? Duration.parse(node.get("delay").asString()) : null);
    }
}
