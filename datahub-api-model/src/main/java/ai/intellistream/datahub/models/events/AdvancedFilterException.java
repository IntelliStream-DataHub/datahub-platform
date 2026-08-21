// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.events;

import java.util.List;

public class AdvancedFilterException extends RuntimeException {

    private List<String> invalidPropertyNames;

    public AdvancedFilterException(List<String> invalidPropertyNames) {
        this.invalidPropertyNames = invalidPropertyNames;
    }
}
