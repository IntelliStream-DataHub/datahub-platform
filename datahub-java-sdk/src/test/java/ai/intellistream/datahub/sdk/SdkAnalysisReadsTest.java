// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.sdk;

import ai.intellistream.datahub.api.responses.DataCollection;
import ai.intellistream.datahub.api.responses.DataRetriever;
import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.api.responses.ResourceNetwork;
import ai.intellistream.datahub.jpa.dto.DatapointAggsDTO;
import ai.intellistream.datahub.models.FetchNearestResourcesForm;
import ai.intellistream.datahub.models.forms.RetrieveFilter;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two SDK reads the analysis service relies on: the nearest-N graph traversal
 * ({@code /resources/fetch-nearest}) and AGGREGATED datapoints ({@code /timeseries/data/list}, which
 * must deserialize into {@code DataCollection<DatapointAggsDTO>} — the min/max/avg/sum shape — not the
 * raw {@code DatapointsCollection} that {@code retrieve()} yields).
 */
class SdkAnalysisReadsTest {

    private static DatahubClient clientFor(HttpServer server) {
        return DatahubClient.create(DatahubConfig.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("test-token")
                .build());
    }

    private static HttpServer stub(String path, String json, AtomicReference<String> capturedBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    @Test
    void fetchNearestSendsEndLabelsAndDeserializesSubgraph() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String network = """
                {"nodes":[{"id":"1","externalId":"focus"},{"id":"2","externalId":"cand"}],
                 "edges":[{"id":"10","start":1,"end":2,"type":"FEEDS"}],
                 "labels":[]}""";
        HttpServer server = stub("/resources/fetch-nearest", network, body);
        try {
            FetchNearestResourcesForm form = new FetchNearestResourcesForm();
            form.setId(1L);
            form.setEndLabels(List.of("TIMESERIES"));
            form.setLimit(5);
            form.setExcludedLabels(List.of("POLICY"));

            ResourceNetwork result = clientFor(server).resources().fetchNearest(form);

            assertTrue(body.get().contains("TIMESERIES"), body.get());
            assertTrue(body.get().contains("POLICY"), body.get());
            assertEquals(2, result.nodes().size());
            assertEquals(1, result.edges().size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retrieveAggregatedDeserializesBucketedPoints() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String data = """
                {"items":[{"id":"1","externalId":"focus","datapoints":[
                  {"timestamp":"1970-01-01T00:00:00Z","min":0.0,"max":2.0,"average":1.5,"sum":3.0},
                  {"timestamp":"1970-01-01T00:01:00Z","min":1.0,"max":3.0,"average":2.5,"sum":5.0}
                ]}]}""";
        HttpServer server = stub("/timeseries/data/list", data, body);
        try {
            DataRetriever<RetrieveFilter> retriever = new DataRetriever<>();
            RetrieveFilter f = new RetrieveFilter();
            f.setId(1L);
            f.setStart(ZonedDateTime.parse("1970-01-01T00:00:00Z"));
            f.setEnd(ZonedDateTime.parse("1970-01-01T00:10:00Z"));
            f.setAggregates(List.of("avg"));
            f.setGranularity("60 second");
            retriever.getItems().add(f);

            DataWrapper<DataCollection<DatapointAggsDTO>> result =
                    clientFor(server).timeseries().retrieveAggregated(retriever);

            assertTrue(body.get().contains("avg"), body.get());
            assertEquals(1, result.getItems().size());
            DataCollection<DatapointAggsDTO> dc = result.getItems().iterator().next();
            assertEquals(2, dc.getDatapoints().size());
            // the aggregate fields deserialized (they'd be lost by the raw retrieve()'s DatapointString)
            assertEquals(1.5, dc.getDatapoints().iterator().next().getAverage());
        } finally {
            server.stop(0);
        }
    }
}
