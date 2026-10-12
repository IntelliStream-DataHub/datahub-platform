// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.records;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.flow.records.RecordsService.Lookup;
import ai.intellistream.datahub.flow.records.RecordsTable.StoredRecord;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Instant;
import java.util.UUID;

/**
 * What runs recorded. Seeing a run's records, or its live view, takes what seeing the run takes;
 * the lookup only ever answers with runs of flows the caller may see.
 */
@RestController
public class RecordsController {

    static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final RecordsService records;

    public RecordsController(RecordsService records) {
        this.records = records;
    }

    /** A run's records, every attempt, in the order they happened; paged with {@code cursor}. */
    @GetMapping("/runs/{id}/records")
    public DataWrapper<StoredRecord> records(@PathVariable UUID id,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) Integer limit) {
        return records.records(id, cursor, limit);
    }

    /**
     * The run's live view, as newline-delimited JSON: {@code run} lines when its state changes,
     * {@code records} lines as its records land, and an {@code end} line once it has ended.
     */
    @GetMapping("/runs/{id}/events")
    public ResponseEntity<StreamingResponseBody> events(@PathVariable UUID id) {
        return ResponseEntity.ok().contentType(NDJSON).body(records.events(id));
    }

    /**
     * Which run last recorded writing {@code timeseries} at {@code at}, with the flow version,
     * parameters and processor versions it ran with, and the earlier writes as history. 404 when no
     * flow the caller can see recorded writing it.
     */
    @GetMapping("/records/lookup")
    public Lookup lookup(@RequestParam String timeseries, @RequestParam Instant at) {
        return records.lookup(timeseries, at);
    }
}
