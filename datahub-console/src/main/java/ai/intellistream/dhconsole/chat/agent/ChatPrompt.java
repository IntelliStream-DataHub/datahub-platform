// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Builds the system prompt for a turn.
 *
 * <h3>Why the date is in here</h3>
 * A model has no clock. Without being told the date it cannot turn "this weekend" or "since
 * Tuesday" into the absolute timestamps the tools require, so it either guesses or asks. Injecting
 * the current date makes relative periods work at all.
 *
 * <h3>Why it is rounded to the hour</h3>
 * The system prompt is the cached prefix on the Anthropic path — a value that changes every request
 * would invalidate the cache every request, which is the classic silent cache killer. Truncating to
 * the hour keeps the prefix byte-identical for the whole hour, comfortably longer than a
 * conversation, at the cost of the model not knowing the exact minute. Ask for "the last 15
 * minutes" and it will reason from the top of the hour.
 */
@Component
public class ChatPrompt {

    private static final DateTimeFormatter TODAY =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter HOUR =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    /**
     * The framing that keeps answers about <em>this</em> data. Questions arrive without context —
     * "give me a summary of this weekend" is about events and datapoints in the tenant, not about
     * the news — and a model with broad world knowledge will happily answer the other reading.
     */
    private static final String BASE = """
            You are a data assistant embedded in the DataHub console, a platform holding industrial \
            time series, events, resources and data sets. The person asking is signed in, and every \
            question is about the data in their own DataHub tenant.

            Read every question that way, even when it sounds general. "What happened this weekend" \
            means events and datapoints recorded in this platform over that period. "Anything \
            unusual?" means unusual in these time series. You have no information about anything \
            outside this platform, so if a question cannot be answered from the tools, say that \
            plainly instead of answering from general knowledge.

            Answer using the tools; never guess at names, ids or values you have not looked up. The \
            data tools are read-only — if the user asks you to create, change or delete anything, \
            say you can only read data here and point them at the relevant part of the console.

            When the question is about cause, influence, correlation or "what moves together with" \
            a series, use analysis_related_series rather than fetching raw datapoints and comparing \
            them yourself — it walks the asset graph for related series and tests each pair \
            statistically, and its lag/significance verdicts are far more reliable than eyeballing \
            numbers.

            You can also help them get around the console with the open_*_view tools, each of which \
            places a button that opens a page: open_events_view (events, pre-filtered), \
            open_resources_view (a resources search), open_insights_view (overlay one or more time \
            series on a single chart to view or compare them — pass externalIds as a list, plus an \
            optional start/end window), and open_analyze_view (the Analyze tab — the related-series \
            analysis for one seed series over a window, e.g. what related series did around an \
            anomaly: pass its externalId as focusExternalId with start/end). Use these whenever the \
            user asks to see, open or compare something — including things you found earlier in this \
            conversation — reusing the same ids and time windows you looked the data up with. They \
            only offer navigation; they do not fetch anything, so they do not replace looking the \
            data up first.

            Numeric ids come back from the tools as JSON strings; treat them as opaque identifiers \
            and repeat them exactly. Pass absolute ISO-8601 UTC timestamps to the tools rather than \
            relative phrases.

            Keep answers short and concrete. Lead with the answer, then any detail that changes what \
            the user would do next. When a tool returns nothing, say so rather than inventing a \
            plausible result.""";

    private final Clock clock;

    /**
     * {@code @Autowired} because there are two constructors: without it Spring cannot choose and
     * falls back to a no-arg one that does not exist. The second exists only so tests can fix time.
     */
    @Autowired
    public ChatPrompt() {
        this(Clock.systemUTC());
    }

    ChatPrompt(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param zone         the user's time zone, so "this weekend" is resolved where they are rather
     *                     than where the server runs
     * @param instructions the agent's own standing instructions, already merged with any
     *                     deployment-wide ones by {@code AgentSettingsResolver}. A parameter rather
     *                     than injected configuration because two agents in the same process are
     *                     told different things — which is the entire point of an agent having a
     *                     definition of its own.
     */
    public String build(ZoneId zone, String instructions) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone)
                .truncatedTo(ChronoUnit.HOURS);

        StringBuilder prompt = new StringBuilder(BASE);
        prompt.append("\n\nToday is ").append(TODAY.format(now))
                .append(" and the time is around ").append(HOUR.format(now))
                .append(" in ").append(zone.getId())
                .append(". Resolve relative periods such as \"this weekend\", \"yesterday\" or ")
                .append("\"last week\" against that.");

        // Agent-specific framing — domain vocabulary, tag conventions, what matters to this
        // customer. Appended rather than replacing the above so the tool discipline and the
        // read-only framing cannot be configured away by accident.
        if (instructions != null && !instructions.isBlank()) {
            prompt.append("\n\n").append(instructions.strip());
        }
        return prompt.toString();
    }
}
