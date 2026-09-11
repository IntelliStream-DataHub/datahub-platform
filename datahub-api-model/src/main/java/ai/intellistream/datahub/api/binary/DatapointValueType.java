// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import java.util.Locale;

/**
 * The seven timeseries value types as the binary contract names them. The ids and table suffixes
 * are the ones the server's {@code TimeseriesValueType} entity uses; a test in datahub-infra pins
 * the two together.
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

    /** TEXT and MIXED carry strings and get the tighter per-frame row cap and the text quota. */
    public boolean carriesText() {
        return this == TEXT || this == MIXED;
    }

    public static DatapointValueType fromId(int id) {
        for (DatapointValueType t : values()) {
            if (t.id == id) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown value type id " + id);
    }

    /** Case-insensitive, as the REST contract spells the type in lowercase and the server in upper. */
    public static DatapointValueType fromName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Value type name is null");
        }
        return valueOf(name.trim().toUpperCase(Locale.ROOT));
    }
}
