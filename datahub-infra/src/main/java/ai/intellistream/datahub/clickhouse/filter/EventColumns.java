// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse.filter;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The columns a filter expression may name, and nothing else.
 *
 * <p>This is an allow-list, so a name that is not here is a rejection rather than a string that
 * reaches the SQL. The physical column name comes from this table too: a caller's identifier is
 * never transformed into a column name, which is what a snake-casing helper used to do — it turned
 * any typo into an unknown-column error from ClickHouse, and made the mapping depend on a string
 * function rather than on a decision.
 *
 * <p>Names are the camelCase ones the JSON contract already uses, so a caller who knows
 * {@code EventFilter} knows these. Resolution is case-insensitive, in the spirit of Postgres
 * folding, and the physical names of the DDL ({@code sub_type}, {@code event_time}) are accepted
 * as aliases when suggesting a correction — see {@link Suggestions}.
 *
 * <p>Deliberately absent: {@code external_id_hash} and the three {@code related_resources_*}
 * arrays. They are derived or structural, and exposing them would invite filters that depend on
 * how the table is built rather than on what an event is.
 */
public final class EventColumns {

    /** One filterable column: what the caller writes, what ClickHouse gets, and its type. */
    public record Column(String logicalName, String physicalName, ValueType type,
                         String clickHouseParamType, boolean nullable) {
    }

    private static final Map<String, Column> BY_LOWER_NAME = new LinkedHashMap<>();

    private static void define(String logical, String physical, ValueType type,
                               String paramType, boolean nullable) {
        BY_LOWER_NAME.put(logical.toLowerCase(Locale.ROOT),
                new Column(logical, physical, type, paramType, nullable));
    }

    static {
        define("id", "id", ValueType.UUID, "UUID", false);
        define("externalId", "external_id", ValueType.STRING, "String", false);
        define("type", "type", ValueType.STRING, "String", false);
        // sub_type and status are the only genuinely nullable columns, which is why IS NULL means
        // something on them and means nothing on a metadata value.
        define("subType", "sub_type", ValueType.STRING, "String", true);
        define("status", "status", ValueType.STRING, "String", true);
        define("source", "source", ValueType.STRING, "String", false);
        define("description", "description", ValueType.STRING, "String", false);
        define("dataSetId", "data_set_id", ValueType.NUMBER, "Int64", false);
        define("eventTime", "event_time", ValueType.DATETIME, "DateTime64(3)", false);
        define("createdTime", "date_created", ValueType.DATETIME, "DateTime64(3)", false);
        define("lastUpdatedTime", "last_updated", ValueType.DATETIME, "DateTime64(3)", false);
    }

    private EventColumns() {
    }

    public static Optional<Column> find(String logicalName) {
        return Optional.ofNullable(BY_LOWER_NAME.get(logicalName.toLowerCase(Locale.ROOT)));
    }

    /** Every filterable name, in declaration order, for error messages and documentation. */
    public static java.util.Collection<String> names() {
        return BY_LOWER_NAME.values().stream().map(Column::logicalName).toList();
    }
}
