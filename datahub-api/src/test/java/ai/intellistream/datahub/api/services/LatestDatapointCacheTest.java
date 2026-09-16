// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.services.ValkeyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The compare-and-set rule both ingest paths now share. It used to be a private method of
 * TimeseriesService, tested only through a datapoint insert, so the binary path could not reach it
 * and the rule itself was never exercised directly.
 */
@ExtendWith(MockitoExtension.class)
class LatestDatapointCacheTest {

    @Mock private ValkeyService valkeyService;
    @InjectMocks private LatestDatapointCache cache;

    @Test
    @DisplayName("An empty key is written without a comparison")
    void firstPointIsWritten() throws Exception {
        when(valkeyService.fetchLatestDatapoint("pump-1")).thenReturn(null);

        cache.update("pump-1", new DatapointString("2026-08-21T10:00:00Z", "1.0"));

        verify(valkeyService).setLatestDatapoint(eq("pump-1"), any(DatapointString.class));
    }

    @Test
    @DisplayName("A newer point replaces the cached one")
    void newerPointWins() throws Exception {
        when(valkeyService.fetchLatestDatapoint("pump-1"))
                .thenReturn(new DatapointString("2026-08-21T10:00:00Z", "1.0"));

        cache.update("pump-1", new DatapointString("2026-08-21T10:00:01Z", "2.0"));

        verify(valkeyService).setLatestDatapoint(eq("pump-1"), any(DatapointString.class));
    }

    @Test
    @DisplayName("An older or equal point leaves the cache alone")
    void olderPointIsIgnored() throws Exception {
        when(valkeyService.fetchLatestDatapoint("pump-1"))
                .thenReturn(new DatapointString("2026-08-21T10:00:05Z", "5.0"));

        cache.update("pump-1", new DatapointString("2026-08-21T10:00:04Z", "4.0"));
        // Equal timestamps are not "after", so a replayed point does not rewrite the key either.
        cache.update("pump-1", new DatapointString("2026-08-21T10:00:05Z", "9.9"));

        verify(valkeyService, never()).setLatestDatapoint(anyString(), any(DatapointString.class));
    }

    @Test
    @DisplayName("Epoch millis are cached as ISO-8601 UTC, the form the reads expect")
    void epochMillisAreRenderedAsIso() throws Exception {
        when(valkeyService.fetchLatestDatapoint("pump-1")).thenReturn(null);

        cache.update("pump-1", 1_766_311_200_000L, "7.5");

        ArgumentCaptor<DatapointString> written = ArgumentCaptor.forClass(DatapointString.class);
        verify(valkeyService).setLatestDatapoint(eq("pump-1"), written.capture());
        assertEquals("2025-12-21T10:00:00Z", written.getValue().getTimestamp());
        assertEquals("7.5", written.getValue().getValue());
    }

    @Test
    @DisplayName("A Valkey failure is logged, not thrown: the datapoints are already accepted")
    void serialisationFailureDoesNotEscape() throws Exception {
        doThrow(JsonProcessingException.class).when(valkeyService).fetchLatestDatapoint("pump-1");

        cache.update("pump-1", new DatapointString("2026-08-21T10:00:00Z", "1.0"));

        verify(valkeyService, never()).setLatestDatapoint(anyString(), any(DatapointString.class));
    }
}
