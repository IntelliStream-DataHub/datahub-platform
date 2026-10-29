// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.api;

import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.events.EventFilter;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The events an {@code events} trigger listens for: the trigger's own filter, matched against each new
 * event as it is recorded, and what a {@code trigger-events} step reads. A field left null or empty asks
 * for anything; the fields are ANDed, the values of one field ORed.
 *
 * @param externalId    external ids; {@code *} and {@code %} match any run of characters, so
 *                      {@code pump-7/*} is a family of lifecycles
 * @param type          types, as patterns like {@code externalId}
 * @param subType       subtypes, likewise
 * @param status        statuses, likewise
 * @param source        sources, likewise
 * @param metadata      entries that must all be present, as {@code key=value}, or a key alone for any value
 * @param excludeSource sources whose events never match, as SQL LIKE patterns with {@code *} for
 *                      {@code %}: {@code agent:*} leaves out what agents wrote
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record EventMatch(List<String> externalId, List<String> type, List<String> subType, List<String> status,
                         List<String> source, List<String> metadata, List<String> excludeSource) {

    /**
     * Whether the event is one of these, as the api's event filter would answer: an external id
     * without a wildcard compared as written, every other value ignoring case. A flow that only takes
     * the event as a signal reads nothing, so what wakes it must be exactly what the filter means.
     */
    public boolean matches(EventModel event) {
        return among(externalId, event.getExternalId(), true) && among(type, event.getType(), false)
                && among(subType, event.getSubType(), false) && among(status, event.getStatus(), false)
                && among(source, event.getSource(), false) && hasMetadata(event.getMetadata())
                && !excluded(event.getSource());
    }

    /**
     * The api's event filter asking for these events, less the excluded sources, which only the advanced
     * filter can say ({@link #exclusions}).
     */
    public EventFilter filter() {
        EventFilter filter = new EventFilter();
        filter.setExternalId(externalId);
        filter.setType(type);
        filter.setSubType(subType);
        filter.setStatus(status);
        filter.setSource(source);
        for (String entry : metadata == null ? List.<String>of() : metadata) {
            int eq = entry.indexOf('=');
            filter.getMetadata().put(eq < 0 ? entry.trim() : entry.substring(0, eq).trim(),
                    eq < 0 ? null : entry.substring(eq + 1).trim());
        }
        return filter;
    }

    /**
     * The api's advanced filter leaving out these source patterns, one clause each, or none: * is SQL's %,
     * and a pattern was checked when the flow was saved, so no quote can be in it. An event with no
     * source is kept.
     */
    public static List<String> exclusions(List<String> excludeSource) {
        return excludeSource == null ? List.of() : excludeSource.stream()
                .map(p -> "(source IS NULL OR source NOT LIKE '" + p.replace("*", "%") + "')").toList();
    }

    /** {@code *} and {@code %} any run of characters; {@code _} is itself. */
    private static boolean among(List<String> wanted, String value, boolean literalKeepsCase) {
        if (wanted == null || wanted.isEmpty()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        for (String pattern : wanted) {
            boolean literal = pattern.indexOf('*') < 0 && pattern.indexOf('%') < 0;
            if (literal && literalKeepsCase ? pattern.equals(value)
                    : Pattern.compile(like(pattern, false), Pattern.CASE_INSENSITIVE).matcher(value).matches()) {
                return true;
            }
        }
        return false;
    }

    /** A pattern as a regular expression: {@code *} and {@code %} any run, {@code _} one character if asked. */
    static String like(String pattern, boolean underscore) {
        StringBuilder regex = new StringBuilder();
        for (char ch : pattern.toCharArray()) {
            regex.append(ch == '*' || ch == '%' ? ".*" : underscore && ch == '_' ? "."
                    : Pattern.quote(String.valueOf(ch)));
        }
        return regex.toString();
    }

    /** Every entry present, as key=value, or a key alone for any value; as read-events reads them. */
    private boolean hasMetadata(Map<String, String> present) {
        if (metadata == null) {
            return true;
        }
        for (String entry : metadata) {
            int eq = entry.indexOf('=');
            String key = (eq < 0 ? entry : entry.substring(0, eq)).trim();
            if (present == null || !present.containsKey(key)
                    || eq >= 0 && !entry.substring(eq + 1).trim().equals(present.get(key))) {
                return false;
            }
        }
        return true;
    }

    /** As read-events' SQL LIKE: * any run of characters, _ one; an event with no source is kept. */
    private boolean excluded(String eventSource) {
        if (excludeSource == null || eventSource == null) {
            return false;
        }
        for (String pattern : excludeSource) {
            if (eventSource.matches(like(pattern, true))) {
                return true;
            }
        }
        return false;
    }
}
