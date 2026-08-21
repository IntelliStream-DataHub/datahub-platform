// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.responses.datapoints.enums;

public enum Aggregates {

    SUM("sum"),
    AVG("avg"),
    MAX("max"),
    MIN("min");
    private final String description;

    Aggregates(String desc) {
        this.description = desc;
    }

    public static Aggregates findByDescription(String text){
        text = text.toLowerCase();
        switch (text) {
            case "sum" -> {
                return SUM;
            }
            case "avg" -> {
                return AVG;
            }
            case "max" -> {
                return MAX;
            }
            case "min" -> {
                return MIN;
            }
            default -> {return null;}
        }
    }
}
