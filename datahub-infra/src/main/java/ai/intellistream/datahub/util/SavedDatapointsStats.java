// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.util;

import ai.intellistream.datahub.helpers.utils.IdGenerator;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Getter
public class SavedDatapointsStats {

    private long sumSavedDatapoints = 0;
    private final long maxDatapoints;
    private final String id;
    private final CompletableFuture<DatapointsResult> firstDataBatch;
    private long totalBatches = 0;
    private final List<String> datapoints = new ArrayList<>();

    public SavedDatapointsStats(long maxDatapoints, CompletableFuture<DatapointsResult> taskFut) {
        this.maxDatapoints = maxDatapoints;
        // No one will guess this cursor id, and it will only exist for a short time
        this.id = IdGenerator.getRandomUUID7AsString();
        this.firstDataBatch = taskFut;
    }

    public void incrementSavedDatapoints() {
        sumSavedDatapoints++;
    }

    public void incrementTotalBatches() {
        totalBatches++;
    }

}
