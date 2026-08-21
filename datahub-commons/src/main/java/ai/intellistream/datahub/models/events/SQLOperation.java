// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.events;

public enum SQLOperation {
    AND_LIST, OR_LIST, START_LIST, END_LIST;

    public String getSymbol(){
        return switch (this) {
            case AND_LIST,OR_LIST -> ",";
            case START_LIST -> "(";
            case END_LIST -> ")";
        };
    }
}
