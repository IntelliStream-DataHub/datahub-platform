// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import ai.intellistream.datahub.api.binary.DatapointValueType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * A row of {@code timeseries_value_type}. The catalogue itself is {@link DatapointValueType}, which
 * this delegates every name and table lookup to; the seven constants below are literals only
 * because a {@code switch} case label has to be a compile-time constant, and
 * {@code DatapointValueTypeParityTest} pins them to the enum.
 */
@Entity
@Table(name = "timeseries_value_type")
@Getter
@Setter
@Slf4j
public class TimeseriesValueType {

    public static final int BIGINT = 1;
    public static final int FLOAT = 2;
    public static final int NUMERIC = 3;
    public static final int TEXT = 4;
    public static final int DECIMAL32 = 5;
    public static final int MIXED = 6;
    public static final int FLOAT32 = 7;

    @Id
    private Integer id;

    private String name;

    /**
     * Human-readable explanation of what this value type maps to in ClickHouse and when to use it.
     * Backfilled by Flyway (V22). The authoritative id-to-ClickHouse-type mapping is
     * {@link #getTableType(String)}; this is documentation for operators and the create-timeseries UI.
     */
    private String description;

    public TimeseriesValueType() {
    }

    public TimeseriesValueType(int valueTypeId) {
        this.id = valueTypeId;
    }

    public TimeseriesValueType(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public static int getValueTypeId(String text){
        // Defensive: never NPE on null and never return the invalid id 0 for an unrecognised
        // type. Both fall back to FLOAT32, the same default Timeseries uses for an unspecified
        // value type, so a stray null/typo can't route datapoints to a non-existent type.
        DatapointValueType type = DatapointValueType.fromNameOrNull(text);
        if (type != null) {
            return type.id();
        }
        if (text != null && !text.isBlank()) {
            log.warn("Unknown timeseries value type '{}', defaulting to FLOAT32", text);
        }
        return FLOAT32;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ai.intellistream.datahub.jpa.domains.TimeseriesValueType)) return false;
        return id != null && id.equals(((ai.intellistream.datahub.jpa.domains.TimeseriesValueType) o).getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    /** Unknown ids read as bigint, which is what the id-keyed switch this replaced fell through to. */
    public static String getTableType(ai.intellistream.datahub.jpa.domains.TimeseriesValueType valueType){
        DatapointValueType type = DatapointValueType.fromIdOrNull(valueType.getId());
        return type == null ? DatapointValueType.BIGINT.tableSuffix() : type.tableSuffix();
    }

    public static String getTableType(String valueType){
        return DatapointValueType.fromId(getValueTypeId(valueType)).tableSuffix();
    }

    // --- Read-query value expressions. MIXED stores numbers and text in two columns
    // (value_numeric / value_text): raw/latest reads coalesce them to a string, while aggregates use
    // the numeric column (text rows are NULL there and fall out of SUM/MIN/MAX/AVG). ---
    public static String latestValueSql(int valueTypeId) {
        return valueTypeId == MIXED ? "coalesce(toString(value_numeric), value_text)" : "toString(value)";
    }

    public static String rawValueSql(int valueTypeId) {
        return valueTypeId == MIXED ? "coalesce(toString(value_numeric), value_text) as value" : "value";
    }

    public static String aggregateValueSql(int valueTypeId) {
        return valueTypeId == MIXED ? "value_numeric as value" : "value";
    }
}
