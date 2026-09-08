// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse.filter;

import ai.intellistream.datahub.filter.FilterParseException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a rejection into a repair.
 *
 * <p>Most of this is a lookup rather than a guess, and that is what makes it trustworthy. The
 * registry already knows every ClickHouse spelling, because translating to it is the registry's
 * job — so someone who writes {@code toDate} has not made a typo, they have used the name of the
 * thing we emit. Inverting the table answers that definitively. Edit distance is the fallback for
 * an actual typo, never the first move.
 *
 * <p>A {@code suggestedQuery} is only produced when one candidate is clearly best. Applying the
 * wrong correction automatically is worse than offering none, so a tie lists candidates and leaves
 * the edit to the caller.
 */
public final class Suggestions {

    /** ClickHouse spellings of things this language already exposes, under Postgres names. */
    private static final Map<String, String> CLICKHOUSE_SPELLINGS = new LinkedHashMap<>();
    /** Names from other SQL dialects that people arrive with. */
    private static final Map<String, String> DIALECT_ALIASES = new LinkedHashMap<>();
    /** Physical DDL column names, which callers copy out of query logs and schema dumps. */
    private static final Map<String, String> PHYSICAL_COLUMNS = new LinkedHashMap<>();

    static {
        CLICKHOUSE_SPELLINGS.put("todate", "to_date");
        CLICKHOUSE_SPELLINGS.put("todatetime", "to_timestamp");
        CLICKHOUSE_SPELLINGS.put("todatetime64", "to_timestamp");
        CLICKHOUSE_SPELLINGS.put("parsedatetimebesteffort", "to_timestamp");
        CLICKHOUSE_SPELLINGS.put("parsedatetimebesteffortornull", "to_timestamp");
        CLICKHOUSE_SPELLINGS.put("toint64", "to_int");
        CLICKHOUSE_SPELLINGS.put("toint32", "to_int");
        CLICKHOUSE_SPELLINGS.put("toint64ornull", "to_int");
        CLICKHOUSE_SPELLINGS.put("touint64", "to_int");
        CLICKHOUSE_SPELLINGS.put("tofloat64", "to_number");
        CLICKHOUSE_SPELLINGS.put("tofloat32", "to_number");
        CLICKHOUSE_SPELLINGS.put("tofloat64ornull", "to_number");
        CLICKHOUSE_SPELLINGS.put("tobool", "to_bool");
        CLICKHOUSE_SPELLINGS.put("mapcontains", "has_key");
        CLICKHOUSE_SPELLINGS.put("toyear", "date_part");
        CLICKHOUSE_SPELLINGS.put("tomonth", "date_part");
        CLICKHOUSE_SPELLINGS.put("todayofmonth", "date_part");
        CLICKHOUSE_SPELLINGS.put("lengthutf8", "length");
        CLICKHOUSE_SPELLINGS.put("lowerutf8", "lower");
        CLICKHOUSE_SPELLINGS.put("upperutf8", "upper");

        DIALECT_ALIASES.put("extract", "date_part");
        DIALECT_ALIASES.put("datepart", "date_part");
        DIALECT_ALIASES.put("year", "date_part");
        DIALECT_ALIASES.put("month", "date_part");
        DIALECT_ALIASES.put("day", "date_part");
        DIALECT_ALIASES.put("to_char", "to_timestamp");
        DIALECT_ALIASES.put("strftime", "to_timestamp");
        DIALECT_ALIASES.put("to_timestamptz", "to_timestamp");
        DIALECT_ALIASES.put("to_bigint", "to_int");
        DIALECT_ALIASES.put("to_float", "to_number");
        DIALECT_ALIASES.put("to_boolean", "to_bool");
        DIALECT_ALIASES.put("haskey", "has_key");
        DIALECT_ALIASES.put("map_contains", "has_key");

        PHYSICAL_COLUMNS.put("external_id", "externalId");
        PHYSICAL_COLUMNS.put("sub_type", "subType");
        PHYSICAL_COLUMNS.put("data_set_id", "dataSetId");
        PHYSICAL_COLUMNS.put("event_time", "eventTime");
        PHYSICAL_COLUMNS.put("date_created", "createdTime");
        PHYSICAL_COLUMNS.put("last_updated", "lastUpdatedTime");
        PHYSICAL_COLUMNS.put("external_id_hash", "externalId");
        PHYSICAL_COLUMNS.put("created_time", "createdTime");
        PHYSICAL_COLUMNS.put("last_updated_time", "lastUpdatedTime");
        PHYSICAL_COLUMNS.put("dataset_id", "dataSetId");
        PHYSICAL_COLUMNS.put("datasetid", "dataSetId");
    }

    private Suggestions() {
    }

    /** A rejection for a function name nothing in the registry matches. */
    public static FilterParseException unknownFunction(String source, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String exact = CLICKHOUSE_SPELLINGS.get(lower);
        if (exact != null) {
            return build(source, name, exact,
                    "'" + name + "' is the ClickHouse spelling. This filter uses PostgreSQL names, "
                            + "so use '" + exact + "'.",
                    "Function names follow PostgreSQL; ClickHouse spellings are mapped internally.");
        }
        String alias = DIALECT_ALIASES.get(lower);
        if (alias != null) {
            return build(source, name, alias,
                    "'" + name + "' is not available; the equivalent here is '" + alias + "'.",
                    "Function names follow PostgreSQL.");
        }
        return nearest(source, name, FunctionRegistry.names(), "function");
    }

    /** A rejection for an identifier that is not a filterable column. */
    public static FilterParseException unknownColumn(String source, String name) {
        String physical = PHYSICAL_COLUMNS.get(name.toLowerCase(Locale.ROOT));
        if (physical != null) {
            return build(source, name, physical,
                    "'" + name + "' is the database column name; filters use '" + physical + "'.",
                    "Filterable fields use the same names as the events API: "
                            + String.join(", ", EventColumns.names()) + ".");
        }
        return nearest(source, name, EventColumns.names(), "field");
    }

    /**
     * Edit distance against the allow-list, used only once the exact tables above have missed.
     *
     * <p>A tie produces candidates and no auto-correction: a repair applied to the wrong token is
     * worse than one the caller has to make.
     */
    private static FilterParseException nearest(String source, String name,
                                                java.util.Collection<String> candidates, String kind) {
        int threshold = name.length() <= 8 ? 2 : 3;
        List<String> best = new ArrayList<>();
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = distance(name.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT));
            if (distance > threshold) {
                continue;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best.clear();
                best.add(candidate);
            } else if (distance == bestDistance) {
                best.add(candidate);
            }
        }
        String known = "Known " + kind + "s: " + String.join(", ", candidates) + ".";
        if (best.isEmpty()) {
            return new FilterParseException("Unknown " + kind + " '" + name + "'.",
                    locate(source, name), name.length(), null, null, known);
        }
        if (best.size() > 1) {
            best.sort(Comparator.naturalOrder());
            List<String> shown = best.subList(0, Math.min(3, best.size()));
            return new FilterParseException("Unknown " + kind + " '" + name + "'. Did you mean "
                    + String.join(", ", shown) + "?",
                    locate(source, name), name.length(), null, null, known);
        }
        return build(source, name, best.getFirst(),
                "Unknown " + kind + " '" + name + "'. Did you mean '" + best.getFirst() + "'?", known);
    }

    private static FilterParseException build(String source, String wrong, String right,
                                              String message, String help) {
        int offset = locate(source, wrong);
        String suggested = offset < 0 ? null : splice(source, offset, wrong.length(), right);
        return new FilterParseException(message, Math.max(offset, 0), wrong.length(),
                right, suggested, help);
    }

    /**
     * Where the offending token sits in the caller's text.
     *
     * <p>Matched on word boundaries so {@code type} inside {@code subType} is not mistaken for it.
     * Positions are recovered here rather than carried on every AST node: only a name that fails
     * to resolve needs one, and threading an offset through the tree would put it into record
     * equality, where it would make two spellings of the same filter compare unequal.
     */
    public static int locate(String source, String token) {
        if (source == null) {
            return -1;
        }
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(token) + "\\b").matcher(source);
        return matcher.find() ? matcher.start() : source.indexOf(token);
    }

    public static String splice(String source, int offset, int length, String replacement) {
        if (source == null || offset < 0 || offset + length > source.length()) {
            return null;
        }
        return source.substring(0, offset) + replacement + source.substring(offset + length);
    }

    /** Damerau-Levenshtein, so a transposed pair counts as one mistake rather than two. */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
