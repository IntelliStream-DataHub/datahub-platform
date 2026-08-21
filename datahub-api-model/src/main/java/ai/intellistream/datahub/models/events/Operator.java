// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.events;

public enum Operator {
    containsAll, containsAny, equals, exists, in, prefix;

    public String getSymbol(){
        return switch (this) {
            case containsAll -> "IN";
            case containsAny -> "IN";
            case equals -> "=";
            case exists -> "=";
            case in -> "IN";
            case prefix -> "LIKE";
        };
    }
}
