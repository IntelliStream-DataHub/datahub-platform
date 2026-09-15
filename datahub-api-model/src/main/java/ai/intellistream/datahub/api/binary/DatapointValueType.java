// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import java.util.Locale;

/**
 * The seven timeseries value types: their ids, their names and the ClickHouse table each lands in.
 *
 * <p>This is the one definition. The ids are the rows Flyway seeds into {@code timeseries_value_type}
 * (V1, V23, V24, V26, V28), and the name and table mappings of the {@code TimeseriesValueType}
 * entity, the {@code AllowedValueType} validator and {@code ValueTypeRecommender} all resolve
 * through here rather than repeating the list. It lives in api-model, the module everything else
 * sits above, and stays framework-free so the Java SDK can use it too.
 *
 * <p>The entity still declares seven {@code static final int} constants of its own, because Java
 * needs a compile-time constant for a {@code switch} case label and several hot paths switch on the
 * id. {@code DatapointValueTypeParityTest} pins those seven numbers to this enum.
 */
public enum DatapointValueType {
    BIGINT(1, "bigint"),
    FLOAT(2, "float"),
    NUMERIC(3, "numeric"),
    TEXT(4, "text"),
    DECIMAL32(5, "decimal32"),
    MIXED(6, "mixed"),
    FLOAT32(7, "float32");

    private final int id;
    private final String tableSuffix;

    DatapointValueType(int id, String tableSuffix) {
        this.id = id;
        this.tableSuffix = tableSuffix;
    }

    public int id() {
        return id;
    }

    /** The ClickHouse table this type's datapoints live in, {@code datapoints_<suffix>}. */
    public String tableName() {
        return "datapoints_" + tableSuffix;
    }

    /** The table name without the {@code datapoints_} prefix. */
    public String tableSuffix() {
        return tableSuffix;
    }

    /** TEXT and MIXED carry strings and get the tighter per-frame row cap and the text quota. */
    public boolean carriesText() {
        return this == TEXT || this == MIXED;
    }

    public static DatapointValueType fromId(int id) {
        DatapointValueType type = fromIdOrNull(id);
        if (type == null) {
            throw new IllegalArgumentException("Unknown value type id " + id);
        }
        return type;
    }

    /** The type with this id, or null when no type has it. */
    public static DatapointValueType fromIdOrNull(int id) {
        for (DatapointValueType t : values()) {
            if (t.id == id) {
                return t;
            }
        }
        return null;
    }

    /** Case-insensitive, as the REST contract spells the type in lowercase and the server in upper. */
    public static DatapointValueType fromName(String name) {
        DatapointValueType type = fromNameOrNull(name);
        if (type == null) {
            throw new IllegalArgumentException("Unknown value type name " + name);
        }
        return type;
    }

    /** The type with this name, or null when the name is absent, blank or unknown. */
    public static DatapointValueType fromNameOrNull(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
