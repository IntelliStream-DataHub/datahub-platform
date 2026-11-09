// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.api.binary.ArrowIpc.Batch;
import ai.intellistream.datahub.api.binary.DatapointFrame;
import ai.intellistream.datahub.api.binary.DatapointFrameWriter;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.FrameLimits;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enough of datahub-api for the source and the sinks, on a loopback HTTP server, answered through a
 * real {@link DatahubClient}: series lookup, the binary read (real frames), the binary write
 * (frames parsed back into points), dataset lookup and event creation.
 */
final class FakeDatahub implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZstdPayloadCodec CODEC = new ZstdPayloadCodec();

    /** A series the fake knows: id, value type, and the points stored in it, as written. */
    record Series(long id, String externalId, DatapointValueType type, List<Long> times,
                  List<Double> values) {
        Series put(long t, double v) {
            times.add(t);
            values.add(v);
            return this;
        }
    }

    final Map<String, Series> series = new LinkedHashMap<>();
    final Map<String, Long> datasets = new LinkedHashMap<>();
    /** Points written through the binary path, by external id. */
    final Map<String, List<double[]>> written = new LinkedHashMap<>();
    final List<JsonNode> events = new ArrayList<>();
    /** Events /events/filter answers from, two per page so that paging is exercised. */
    final List<Map<String, Object>> stored = new ArrayList<>();
    /** Every POST /events/filter body, for what the source asked. */
    final List<JsonNode> filterRequests = new ArrayList<>();
    final List<String> requests = new ArrayList<>();
    /** When set, the binary write answers this status with a problem document. */
    Integer writeStatus;

    private final HttpServer server;
    private final DatahubClient client;

    FakeDatahub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = DatahubClient.create(DatahubConfig.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .token("test-token").build());
    }

    DatahubClient client() {
        return client;
    }

    Series add(String externalId, DatapointValueType type) {
        Series s = new Series(1000 + series.size(), externalId, type, new ArrayList<>(),
                new ArrayList<>());
        series.put(externalId, s);
        return s;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(exchange.getRequestMethod() + " " + path);
        byte[] body = exchange.getRequestBody().readAllBytes();
        try {
            switch (path) {
                case "/timeseries/byids" -> json(exchange, 200, byIds(body));
                case "/timeseries/data/list/binary" -> read(exchange, body);
                case "/timeseries/data/binary" -> write(exchange, body);
                case "/datasets/byids" -> json(exchange, 200, datasetsByIds(body));
                case "/events/filter" -> json(exchange, 200, filterEvents(body));
                case "/events/create" -> {
                    JsonNode items = JSON.readTree(body).get("items");
                    items.forEach(events::add);
                    json(exchange, 200, Map.of("items", items));
                }
                default -> json(exchange, 404, Map.of("type", "about:blank", "status", 404));
            }
        } catch (RuntimeException e) {
            json(exchange, 500, Map.of("type", "about:blank", "status", 500, "detail", String.valueOf(e)));
        }
    }

    /** An event /events/filter can return, recorded when it happened. */
    void storeEvent(String externalId, String type, long eventTime, String status) {
        storeEvent(externalId, type, eventTime, eventTime, status);
    }

    /** An event /events/filter can return, recorded at {@code createdTime}. */
    void storeEvent(String externalId, String type, long eventTime, long createdTime, String status) {
        stored.add(Map.of("id", "e" + stored.size(), "externalId", externalId, "type", type,
                "eventTime", eventTime, "createdTime", Instant.ofEpochMilli(createdTime).toString(),
                "status", status, "metadata", Map.of("k", "v")));
    }

    /** By type and an inclusive window on the event time or the created time, as the api filters, two at a time. */
    private Map<String, Object> filterEvents(byte[] body) {
        JsonNode request = JSON.readTree(body);
        filterRequests.add(request);
        JsonNode filter = request.get("filter");
        JsonNode types = filter.get("type");
        boolean byCreated = filter.hasNonNull("createdTime");
        JsonNode window = byCreated ? filter.get("createdTime") : filter.get("eventTime");
        long min = instant(window.get("min"));
        long max = instant(window.get("max"));
        List<Map<String, Object>> matching = stored.stream()
                .filter(e -> types == null || types.isNull() || types.valueStream()
                        .anyMatch(t -> t.stringValue().equals(e.get("type"))))
                .filter(e -> {
                    long t = byCreated ? Instant.parse((String) e.get("createdTime")).toEpochMilli()
                            : (long) e.get("eventTime");
                    return t >= min && t <= max;
                })
                .toList();
        JsonNode cursor = request.get("cursor");
        int from = cursor == null || cursor.isNull() ? 0 : Integer.parseInt(cursor.stringValue());
        int to = Math.min(matching.size(), from + 2);
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", matching.subList(from, to));
        if (to < matching.size()) {
            page.put("nextCursor", Integer.toString(to));
        }
        return page;
    }

    private Map<String, Object> byIds(byte[] body) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode id : JSON.readTree(body).get("items")) {
            Series s = series.get(id.get("externalId").stringValue());
            if (s != null) {
                items.add(Map.of("id", s.id(), "externalId", s.externalId(),
                        "valueType", s.type().name().toLowerCase(), "unit", "u"));
            }
        }
        return Map.of("items", items);
    }

    private Map<String, Object> datasetsByIds(byte[] body) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode id : JSON.readTree(body).get("items")) {
            Long found = datasets.get(id.get("externalId").stringValue());
            if (found != null) {
                items.add(Map.of("id", found, "externalId", id.get("externalId").stringValue(),
                        "name", "ds"));
            }
        }
        return Map.of("items", items);
    }

    /** Real frames for every known series' stored points in its item's window, then the marker. */
    private void read(HttpExchange exchange, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (JsonNode item : JSON.readTree(body).get("items")) {
            Series s = series.get(item.get("externalId").stringValue());
            if (s == null) {
                continue;
            }
            long from = instant(item.get("start"));
            long to = instant(item.get("end"));
            // A frame keeps one value per timestamp, so a timestamp stored twice starts another
            // frame: that is how a point written twice and not yet merged reads back.
            DatapointFrameWriter writer = DatapointFrameWriter.forType(s.type()).series(s.id(), s.externalId());
            Set<Long> inFrame = new HashSet<>();
            for (int i = 0; i < s.times().size(); i++) {
                long t = s.times().get(i);
                if (t < from || t >= to) {
                    continue;
                }
                if (!inFrame.add(t)) {
                    out.write(writer.build(CODEC));
                    writer = DatapointFrameWriter.forType(s.type()).series(s.id(), s.externalId());
                    inFrame.clear();
                    inFrame.add(t);
                }
                writer.add(s.id(), t, format(s.type(), s.values().get(i)));
            }
            if (writer.rowCount() > 0) {
                out.write(writer.build(CODEC));
            }
        }
        out.write(FrameLimits.END_OF_RESPONSE);
        exchange.getResponseHeaders().set("Content-Type", FrameLimits.MEDIA_TYPE);
        exchange.sendResponseHeaders(200, out.size());
        exchange.getResponseBody().write(out.toByteArray());
        exchange.close();
    }

    private void write(HttpExchange exchange, byte[] body) throws IOException {
        if (writeStatus != null) {
            json(exchange, writeStatus, Map.of("type", "https://intellistream.ai/errors/refused",
                    "status", writeStatus, "detail", "refused by the test"));
            return;
        }
        for (DatapointFrame frame : DatapointFrame.parseAll(body, CODEC)) {
            for (Batch batch : frame.batches()) {
                for (int r = 0; r < batch.rows(); r++) {
                    long id = batch.columns()[0].data().getLong(r * 8);
                    String externalId = frame.externalIds()[0];
                    for (int k = 0; k < frame.seriesCount(); k++) {
                        if (frame.seriesIds()[k] == id) {
                            externalId = frame.externalIds()[k];
                        }
                    }
                    long t = batch.columns()[1].data().getLong(r * 8);
                    double v = frame.valueType() == DatapointValueType.FLOAT32
                            ? batch.columns()[2].data().getFloat(r * 4)
                            : batch.columns()[2].data().getDouble(r * 8);
                    written.computeIfAbsent(externalId, k -> new ArrayList<>()).add(new double[]{t, v});
                }
            }
        }
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private static String format(DatapointValueType type, double v) {
        return switch (type) {
            case BIGINT -> Long.toString((long) v);
            default -> Double.toString(v);
        };
    }

    private static long instant(JsonNode node) {
        if (node.isNumber()) {
            return node.longValue();
        }
        String s = node.stringValue();
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (RuntimeException e) {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        }
    }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
                status >= 400 ? "application/problem+json" : "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
