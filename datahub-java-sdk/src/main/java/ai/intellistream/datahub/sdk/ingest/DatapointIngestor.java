// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.ingest;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.api.responses.DatapointsCollection;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import tools.jackson.databind.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends datapoints concurrently: splits them into batches of at most {@code batchSize} (slicing
 * large collections) and runs the batches through {@link BatchExecutor}. Batches are independent,
 * so there is no cross-batch ordering guarantee — which is fine for timestamped data.
 */
public final class DatapointIngestor {

    private final ApiHttp http;
    private final String path;
    private final JavaType responseType; // DataWrapper<String>

    public DatapointIngestor(ApiHttp http, String path) {
        this.http = http;
        this.path = path;
        this.responseType = http.typeFactory().constructParametricType(DataWrapper.class, String.class);
    }

    public IngestResult ingest(List<DatapointsCollection> data, IngestOptions options) {
        List<BatchExecutor.Task> tasks = new ArrayList<>();
        for (Batch batch : chunk(data, options.batchSize())) {
            DataWrapper<DatapointsCollection> body =
                    new DataWrapper<DatapointsCollection>().setItems(batch.collections());
            tasks.add(new BatchExecutor.Task(batch.count(), () -> http.post(path, body, responseType)));
        }
        return BatchExecutor.execute(tasks, options);
    }

    /** Split into batches of at most {@code batchSize} datapoints, slicing collections as needed. */
    static List<Batch> chunk(List<DatapointsCollection> data, int batchSize) {
        List<Batch> batches = new ArrayList<>();
        List<DatapointsCollection> current = new ArrayList<>();
        int count = 0;
        for (DatapointsCollection collection : data) {
            List<DatapointString> datapoints = collection.getDatapoints();
            if (datapoints == null || datapoints.isEmpty()) {
                continue;
            }
            int i = 0;
            while (i < datapoints.size()) {
                if (count >= batchSize) {
                    batches.add(new Batch(current, count));
                    current = new ArrayList<>();
                    count = 0;
                }
                int take = Math.min(batchSize - count, datapoints.size() - i);
                DatapointsCollection slice = new DatapointsCollection();
                slice.setId(collection.getId());
                slice.setExternalId(collection.getExternalId());
                slice.setUnit(collection.getUnit());
                slice.setUnitExternalId(collection.getUnitExternalId());
                slice.setDatapoints(new ArrayList<>(datapoints.subList(i, i + take)));
                current.add(slice);
                count += take;
                i += take;
            }
        }
        if (count > 0) {
            batches.add(new Batch(current, count));
        }
        return batches;
    }

    private record Batch(List<DatapointsCollection> collections, int count) {
    }
}
