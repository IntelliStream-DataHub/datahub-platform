// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.responses.DataCollection;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.models.IdCollection;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /timeseries/data/latest} reads back as {@link DatapointString}, not as the swagger doc
 * type that shares the name {@code DatapointDTO}.
 *
 * <p>Two types are called {@code DatapointDTO}. The api's handler signature names the marker
 * interface in {@code datahub-infra}, implemented by {@code DatapointBigIntDTO} and friends, which
 * serialize an ISO-8601 {@code timestamp} and a value typed by the series. The other,
 * {@code api.responses.swaggerdto.DatapointDTO}, exists only to give springdoc a schema to render
 * and has a numeric {@code timestamp} plus a separate {@code isoTime}: reading the wire into it
 * fails on the first datapoint. Hence the timestamp-and-value-as-text pair, which is what the
 * datapoint reads already use.
 */
class TimeseriesLatestTest {

    /** A response in the shape the api's fetchLatestDatapoint actually serializes. */
    private static final String RESPONSE = """
            {"items":[
              {"id":42,"externalId":"engine_temperature","unit":"DEG_C",
               "datapoints":[{"timestamp":"2026-09-15T10:00:00Z","value":97.4}]},
              {"id":43,"externalId":"pump_starts","unit":null,
               "datapoints":[{"timestamp":"2026-09-15T09:58:12Z","value":4181}]}
            ]}""";

    @Test
    @DisplayName("the latest point parses, whatever value type the series carries")
    void latestParsesTheWireShape() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/timeseries/data/latest", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] out = RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (var os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        try {
            DatahubClient client = DatahubClient.create(DatahubConfig.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .token("test-token")
                    .build());

            DataWrapper<DataCollection<DatapointString>> latest = client.timeseries().latest(List.of(
                    IdCollection.createFromExternalId("engine_temperature"),
                    IdCollection.createFromExternalId("pump_starts")));

            assertEquals(2, latest.getItems().size());
            List<DataCollection<DatapointString>> items = List.copyOf(latest.getItems());

            DataCollection<DatapointString> temperature = items.get(0);
            assertEquals("engine_temperature", temperature.getExternalId());
            DatapointString point = temperature.getDatapoints().get(0);
            assertEquals("2026-09-15T10:00:00Z", point.getTimestamp(),
                    "the timestamp is ISO-8601, which the swagger doc type's numeric field could not hold");
            assertEquals(97.4, Double.parseDouble(point.getValue()));

            // A whole-number series serializes an integer; it has to survive the same String field.
            DatapointString starts = items.get(1).getDatapoints().get(0);
            assertEquals(4181L, Long.parseLong(starts.getValue()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a series holding no datapoints is omitted, not returned empty")
    void seriesWithNoDataAreOmitted() throws Exception {
        // The api skips a series whose latest value is null, so the response is shorter than the
        // request. Callers must match on externalId rather than by position.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/timeseries/data/latest", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] out = """
                    {"items":[{"id":42,"externalId":"engine_temperature",
                     "datapoints":[{"timestamp":"2026-09-15T10:00:00Z","value":97.4}]}]}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (var os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        try {
            DatahubClient client = DatahubClient.create(DatahubConfig.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .token("test-token")
                    .build());

            DataWrapper<DataCollection<DatapointString>> latest = client.timeseries().latest(List.of(
                    IdCollection.createFromExternalId("engine_temperature"),
                    IdCollection.createFromExternalId("never_written_to")));

            assertEquals(1, latest.getItems().size(), "asked for two, one has no data");
            assertTrue(latest.getItems().stream()
                            .noneMatch(c -> "never_written_to".equals(c.getExternalId())),
                    "the empty series is absent rather than present with an empty list");
        } finally {
            server.stop(0);
        }
    }
}
