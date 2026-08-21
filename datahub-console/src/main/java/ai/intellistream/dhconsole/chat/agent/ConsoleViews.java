// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.agent;

import ai.intellistream.dhconsole.chat.llm.LlmBlock;
import ai.intellistream.dhconsole.chat.llm.LlmToolDef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Turns a chat turn into an offer to open the same thing in a console page.
 *
 * <p>Two paths produce a {@link ConsoleView}:
 * <ul>
 *   <li><b>implicit</b> ({@link #from}) — the model ran a data query (e.g. {@code event_filter}) and
 *       we offer to open its results in the matching page, derived from the call's own arguments;</li>
 *   <li><b>explicit</b> ({@link #fromLocalCall}) — the model called one of the console-owned
 *       navigation tools ({@code open_*_view}) purely to take the user somewhere.</li>
 * </ul>
 *
 * <p>Either way the navigation intent is derived from arguments, never from free-text the model
 * writes: a page and its filter come from a fixed vocabulary, so the view cannot disagree with the
 * answer it accompanies. The filter map's keys are a per-page contract the target page's
 * {@code applyPendingView()} handler must understand.
 *
 * <p>Adding a page = add a {@link NavTool} entry here and the matching handoff on that page (plus its
 * URL in the chat panel's {@code VIEW_PAGES}).
 */
@Slf4j
@Component
public class ConsoleViews {

    /**
     * The {@code event_filter} arguments the events page can act on. {@code groupBy}/{@code limit}
     * shape the tool's answer rather than the filter, so they are dropped. Reused by the implicit
     * offer and the {@code open_events_view} tool.
     */
    private static final List<String> EVENT_FILTER_FIELDS = List.of(
            "type", "subType", "source", "externalId", "dataSetId", "start", "end");

    /**
     * The two arguments {@code event_filter} narrows datasets with, both folded into the page's one
     * dataset field by {@link #mergeDataSetArgs}.
     */
    private static final List<String> EVENT_DATASET_ARGS = List.of("dataSetId", "dataSetExternalId");

    /**
     * A console-owned navigation tool: {@code name} the model calls, the {@code page} it opens, the
     * argument {@code fields} promoted into the view's filter (in the page's vocabulary), and the
     * {@code description}/{@code schema} advertised to the model.
     */
    private record NavTool(String name, String page, List<String> fields, String description, String schema) {
    }

    private static final List<NavTool> LOCAL_TOOLS = List.of(
            new NavTool("open_events_view", "events", EVENT_FILTER_FIELDS,
                    "Give the user a button that opens the console's events page, pre-filtered to a "
                            + "set of events. Use it whenever they ask to see, open or navigate to "
                            + "events — including events you found earlier in this conversation — "
                            + "passing the same filters you used with event_filter. This does not "
                            + "fetch data; it only offers the navigation.",
                    """
                    {"type":"object","properties":{
                      "type":{"type":"string","description":"Event type, e.g. ALARM"},
                      "subType":{"type":"string"},
                      "source":{"type":"string"},
                      "externalId":{"type":"string","description":"Event externalId; `*` is a wildcard, so 'work_order_*' is a prefix search"},
                      "dataSetId":{"type":"string","description":"Data set id, as returned by the tools"},
                      "start":{"type":"string","description":"ISO-8601 UTC start of the window"},
                      "end":{"type":"string","description":"ISO-8601 UTC end of the window"}
                    }}"""),
            new NavTool("open_resources_view", "resources", List.of("query"),
                    "Give the user a button that opens the console's resources page with a search "
                            + "already run. Use it when they ask to see, open, browse or find "
                            + "resources/assets. Pass 'query' — free text such as a name or tag "
                            + "(the same text you would give resource_search).",
                    """
                    {"type":"object","properties":{
                      "query":{"type":"string","description":"Free-text search for resources by name or tag"}
                    }}"""),
            new NavTool("open_insights_view", "insights", List.of("externalIds", "start", "end"),
                    "Give the user a button that opens the Insights chart with one or more time "
                            + "series overlaid on one chart, optionally over a time window. Use it "
                            + "when they want to view or compare specific series you have identified "
                            + "— pass externalIds as a list of the series' external ids, and "
                            + "optionally start/end (ISO-8601 UTC) to set the window.",
                    """
                    {"type":"object","properties":{
                      "externalIds":{"type":"array","items":{"type":"string"},"description":"External ids of the time series to overlay on one chart"},
                      "start":{"type":"string","description":"ISO-8601 UTC start of the window"},
                      "end":{"type":"string","description":"ISO-8601 UTC end of the window"}
                    }}"""),
            new NavTool("open_analyze_view", "analyze",
                    List.of("focusExternalId", "start", "end", "limit"),
                    "Give the user a button that opens the Insights analysis for one seed time "
                            + "series over a period: the nearest related series and how they moved "
                            + "together. Use it when they want to see what related or nearby time "
                            + "series did over a window — e.g. around an anomaly. Pass the seed "
                            + "series' externalId as focusExternalId and the period as start/end "
                            + "(ISO-8601 UTC); optionally limit for how many related series.",
                    """
                    {"type":"object","properties":{
                      "focusExternalId":{"type":"string","description":"External id of the seed/anomaly time series"},
                      "start":{"type":"string","description":"ISO-8601 UTC start of the window"},
                      "end":{"type":"string","description":"ISO-8601 UTC end of the window"},
                      "limit":{"type":"integer","description":"How many related series to consider (nearest-N), default 10"}
                    }}"""));

    private final JsonMapper json;

    public ConsoleViews(JsonMapper json) {
        this.json = json;
    }

    /**
     * Tools the console runs itself and never sends to datahub-api. Advertised to the model next to
     * the data tools so it can navigate when asked, reusing the same vocabulary it queried with.
     */
    public List<LlmToolDef> localToolDefs() {
        return LOCAL_TOOLS.stream()
                .map(t -> new LlmToolDef(t.name(), t.description(), t.schema()))
                .toList();
    }

    public boolean isLocalTool(String name) {
        return LOCAL_TOOLS.stream().anyMatch(t -> t.name().equals(name));
    }

    /** An offer derived from a data query the model already ran ({@code event_filter}). */
    public Optional<ConsoleView> from(LlmBlock.ToolUse call, LlmBlock.ToolResult result) {
        if (!"event_filter".equals(call.name()) || result.isError()) {
            return Optional.empty();
        }
        Map<String, Object> filter = extractFilter(call.args(), EVENT_FILTER_FIELDS);
        mergeDataSetArgs(call.args(), filter);
        // An unfiltered "everything" view is what the page already shows on its own.
        if (filter.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ConsoleView("events", filter, countOf(result)));
    }

    /**
     * The view for an explicit {@code open_*_view} call. Unlike {@link #from}, an empty filter is
     * honoured — the user asked to open the page, so an unfiltered view is a valid answer. There is
     * no count because nothing was queried.
     */
    public Optional<ConsoleView> fromLocalCall(LlmBlock.ToolUse call) {
        return LOCAL_TOOLS.stream()
                .filter(t -> t.name().equals(call.name()))
                .findFirst()
                .map(t -> new ConsoleView(t.page(), extractFilter(call.args(), t.fields()), null));
    }

    /**
     * Carry {@code event_filter}'s dataset narrowing over to the page.
     *
     * <p>The api keeps ids and externalIds in separate arguments, because an all-digit externalId is
     * legal and a single flattened list would have to guess which it was handed. The page instead
     * has one comma-separated field that resolves either: {@code buildQuery} splits on commas and
     * looks each part up in a name-and-externalId index, passing bare digits through as ids. So the
     * two arguments fold into the one field here — under the api's own names, not names this class
     * invents.
     *
     * <p>Values are joined as given, never parsed. The page wants the text it would have been typed
     * as, and parsing an id to hand it back as text can only lose on the way through.
     */
    private void mergeDataSetArgs(Map<String, Object> args, Map<String, Object> filter) {
        String joined = EVENT_DATASET_ARGS.stream()
                .map(args::get)
                .filter(Objects::nonNull)
                .flatMap(value -> value instanceof Collection<?> c ? c.stream() : Stream.of(value))
                .map(String::valueOf)
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .distinct()
                .collect(Collectors.joining(","));
        if (!joined.isEmpty()) {
            filter.put("dataSetId", joined);
        }
    }

    private Map<String, Object> extractFilter(Map<String, Object> args, List<String> fields) {
        Map<String, Object> filter = new LinkedHashMap<>();
        for (String field : fields) {
            Object value = args.get(field);
            if (value != null && !String.valueOf(value).isBlank()) {
                filter.put(field, value);
            }
        }
        return filter;
    }

    /**
     * {@code EventQueryResult} carries {@code returned} when drilling down and {@code total} when
     * aggregating. The count is cosmetic, so a result that was truncated into invalid JSON just
     * yields no number rather than failing the turn.
     */
    private Integer countOf(LlmBlock.ToolResult result) {
        try {
            JsonNode parsed = json.readTree(result.content());
            for (String field : List.of("returned", "total")) {
                JsonNode value = parsed.path(field);
                if (value.isNumber()) {
                    return value.asInt();
                }
            }
        } catch (JacksonException e) {
            log.debug("Could not read a count out of the event_filter result", e);
        }
        return null;
    }
}
