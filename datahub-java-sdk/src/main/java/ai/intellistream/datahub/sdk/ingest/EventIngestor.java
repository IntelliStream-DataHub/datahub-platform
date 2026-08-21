// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk.ingest;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.sdk.http.ApiHttp;
import tools.jackson.databind.JavaType;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends events concurrently: chunks them into batches of at most {@code batchSize} and runs the
 * batches through {@link BatchExecutor}.
 */
public final class EventIngestor {

    private final ApiHttp http;
    private final String path;
    private final JavaType responseType; // DataWrapper<EventModel>

    public EventIngestor(ApiHttp http, String path) {
        this.http = http;
        this.path = path;
        this.responseType = http.typeFactory().constructParametricType(DataWrapper.class, EventModel.class);
    }

    public IngestResult ingest(List<EventModel> events, IngestOptions options) {
        List<BatchExecutor.Task> tasks = new ArrayList<>();
        int batchSize = options.batchSize();
        for (int i = 0; i < events.size(); i += batchSize) {
            List<EventModel> batch = new ArrayList<>(events.subList(i, Math.min(i + batchSize, events.size())));
            DataWrapper<EventModel> body = new DataWrapper<EventModel>().setItems(batch);
            tasks.add(new BatchExecutor.Task(batch.size(), () -> http.post(path, body, responseType)));
        }
        return BatchExecutor.execute(tasks, options);
    }
}
