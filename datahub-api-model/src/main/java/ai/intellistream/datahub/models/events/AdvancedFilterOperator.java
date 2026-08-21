// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.events;

import lombok.Data;

import java.util.List;

@Data
public class AdvancedFilterOperator {

    private Operator operator;
    private List<String> property;
    private String value;
    private List<String> values;

}
