// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse.filter;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The functions a filter expression may call, named as PostgreSQL names them.
 *
 * <p>The language is Postgres-flavoured, so a caller writes {@code to_timestamp} and never learns
 * that ClickHouse spells it {@code parseDateTimeBestEffortOrNull}. This table is the whole of that
 * translation, and it is an allow-list: an unrecognised name is a rejection, not a passthrough, so
 * the enormous surface of ClickHouse's built-ins is unreachable by construction rather than by a
 * list of things to block.
 *
 * <p>Where Postgres has no function — it casts with {@code ::int} and {@code ::boolean} — the same
 * {@code to_*} convention fills the gap, and the {@code ::} sugar resolves to the same entries.
 *
 * <p><b>Every converter applied to a column emits an {@code …OrNull} form.</b> The bare
 * {@code toDateTime}/{@code toInt64} throw on the first value that will not parse, and a metadata
 * value is free text: one bad row would abort the whole query rather than simply not matching it.
 * NULL compares false, which is the answer a caller wants for "this row's metadata is not a date".
 */
public final class FunctionRegistry {

    /**
     * @param name        the PostgreSQL-flavoured name callers write
     * @param minArgs     inclusive
     * @param maxArgs     inclusive
     * @param returns     the type of the call, used to type-check the comparison around it
     */
    public record Fn(String name, int minArgs, int maxArgs, ValueType returns) {
    }

    private static final Map<String, Fn> BY_NAME = new LinkedHashMap<>();

    private static void define(String name, int minArgs, int maxArgs, ValueType returns) {
        BY_NAME.put(name, new Fn(name, minArgs, maxArgs, returns));
    }

    static {
        // Converters. These are what make a metadata value usable as anything but a string.
        define("to_timestamp", 1, 1, ValueType.DATETIME);
        define("to_date", 1, 1, ValueType.DATETIME);
        define("to_number", 1, 1, ValueType.NUMBER);
        define("to_int", 1, 1, ValueType.NUMBER);
        define("to_bool", 1, 1, ValueType.BOOLEAN);

        // date_part('year'|'month'|'day', x) — one Postgres entry where ClickHouse has three
        // functions. The part is validated against a closed set, never passed through.
        define("date_part", 2, 2, ValueType.NUMBER);

        // A missing map key yields '' rather than NULL, so existence needs asking for directly.
        define("has_key", 1, 1, ValueType.BOOLEAN);

        define("lower", 1, 1, ValueType.STRING);
        define("upper", 1, 1, ValueType.STRING);
        define("length", 1, 1, ValueType.NUMBER);
        define("now", 0, 0, ValueType.DATETIME);
    }

    /** The parts {@code date_part} accepts. Anything else is a rejection naming these three. */
    public static final Set<String> DATE_PARTS = Set.of("year", "month", "day");

    /** Cast target types, mapped to the converter each is sugar for. */
    private static final Map<String, String> CAST_TARGETS = Map.of(
            "int", "to_int",
            "bigint", "to_int",
            "integer", "to_int",
            "float", "to_number",
            "numeric", "to_number",
            "boolean", "to_bool",
            "bool", "to_bool",
            "date", "to_date",
            "timestamp", "to_timestamp");

    private FunctionRegistry() {
    }

    public static Optional<Fn> find(String name) {
        return Optional.ofNullable(BY_NAME.get(name.toLowerCase(Locale.ROOT)));
    }

    /** The converter a {@code ::type} cast is shorthand for, if the target type is known. */
    public static Optional<String> converterForCast(String typeName) {
        return Optional.ofNullable(CAST_TARGETS.get(typeName.toLowerCase(Locale.ROOT)));
    }

    public static Set<String> castTargets() {
        return CAST_TARGETS.keySet();
    }

    public static java.util.Collection<String> names() {
        return BY_NAME.keySet();
    }
}
