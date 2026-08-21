// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.timeseries.enums;

import java.util.Arrays;

public enum TableEngine {

    MERGETREE, // Clickhouse MergeTree
    HYPERTABLE; // TimescaleDB HyperTable (Not in use)

    public static TableEngine from(String tableEngine){
        tableEngine = tableEngine.toUpperCase();
        if (tableEngine.equals("HYPERTABLE")) {
            return HYPERTABLE;
        }
        return MERGETREE;
    }

    public static TableEngine fromId(int number){
        return Arrays.stream(values())
                .filter( it -> it.ordinal() == number)
                .findFirst()
                .orElse(null);
    }
}
