// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.domains;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

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
        // type. Both fall back to FLOAT32 — the same default Timeseries uses for an unspecified
        // value type — so a stray null/typo can't route datapoints to a non-existent type.
        if (text == null) {
            return FLOAT32;
        }
        switch (text.toUpperCase()) {
            case "BIGINT" -> {
                return BIGINT;
            }
            case "FLOAT" -> {
                return FLOAT;
            }
            case "FLOAT32" -> {
                return FLOAT32;
            }
            case "NUMERIC" -> {
                return NUMERIC;
            }
            case "DECIMAL32" -> {
                return DECIMAL32;
            }
            case "MIXED" -> {
                return MIXED;
            }
            case "TEXT" -> {
                return TEXT;
            }
        }
        log.warn("Unknown timeseries value type '{}', defaulting to FLOAT32", text);
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

    public static String getTableType(ai.intellistream.datahub.jpa.domains.TimeseriesValueType valueType){
        String type = "bigint";
        switch (valueType.getId()){
            case 2 -> type = "float";
            case 3 -> type = "numeric";
            case 4 -> type = "text";
            case 5 -> type = "decimal32";
            case 6 -> type = "mixed";
            case 7 -> type = "float32";
        }
        return type;
    }

    public static String getTableType(String valueType){
        String type = "bigint";
        int valueTypeId = getValueTypeId(valueType);
        switch (valueTypeId){
            case 2 -> type = "float";
            case 3 -> type = "numeric";
            case 4 -> type = "text";
            case 5 -> type = "decimal32";
            case 6 -> type = "mixed";
            case 7 -> type = "float32";
        }
        return type;
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
