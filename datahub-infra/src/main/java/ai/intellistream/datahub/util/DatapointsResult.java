// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.util;

import ai.intellistream.datahub.jpa.domains.NodeEntity;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class DatapointsResult {

    private long totalBatches = 0;
    private TimeseriesEntity node;
    private String cursorId;
    private final List<String> datapoints = new ArrayList<>();
    private Collection<String> aggregates;

    public DatapointsResult(long totalBatches, TimeseriesEntity node, String cursorId, Collection<String> aggregates) {
        this.totalBatches = totalBatches;
        this.node = node;
        this.cursorId = cursorId;
        this.aggregates = aggregates;
    }

    public boolean hasAggregates(){
        return aggregates != null && !aggregates.isEmpty();
    }

}
