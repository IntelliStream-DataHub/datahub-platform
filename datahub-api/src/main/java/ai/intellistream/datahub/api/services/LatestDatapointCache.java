// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.helpers.datetime.DateTimeHandler;
import ai.intellistream.datahub.services.ValkeyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The per-series latest value in Valkey, written only when the new point is later than the cached
 * one. Both ingest paths go through here, so the rule is stated once: it used to live privately in
 * {@code TimeseriesService} as well, which meant the JSON and binary paths could drift apart while
 * writing the same key.
 *
 * <p>Read-then-write is not atomic, so two writers racing on one series can leave the earlier point
 * cached. That was true of the original too. The cache is a convenience for
 * {@code /timeseries/data/latest}; ClickHouse remains the record.
 */
@Service
@Slf4j
public class LatestDatapointCache {

    private final ValkeyService valkeyService;

    public LatestDatapointCache(ValkeyService valkeyService) {
        this.valkeyService = valkeyService;
    }

    /** Timestamps are rendered as ISO-8601 UTC, the form the fan-out and the JSON reads use. */
    public void update(String externalId, long epochMillis, String value) {
        String iso = Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        update(externalId, new DatapointString(iso, value));
    }

    public void update(String externalId, DatapointString dp) {
        try {
            DatapointString cached = valkeyService.fetchLatestDatapoint(externalId);
            if (cached == null) {
                valkeyService.setLatestDatapoint(externalId, dp);
                return;
            }
            ZonedDateTime latest = DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(dp.getTimestamp());
            ZonedDateTime saved = DateTimeHandler.fromEpochUTCTimeAsZonedDateTime(cached.getTimestamp());
            if (latest.isAfter(saved)) {
                valkeyService.setLatestDatapoint(externalId, dp);
            }
        } catch (JsonProcessingException e) {
            log.error(e.getMessage(), e);
        }
    }
}
