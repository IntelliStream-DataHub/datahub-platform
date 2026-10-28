// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm.mcp;

import ai.intellistream.datahub.llm.LlmToolDef;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The HTTP and JSON-RPC under both MCP clients: one place that sends a message, bounds the reply in
 * time and size, reads it whether the server framed it as JSON or as server-sent events, and turns
 * the server's statuses into {@link McpException}s.
 *
 * <p>Headers are an argument of every exchange, never a field, so a credential lives with the
 * client that owns it: the platform client puts the caller's token on each call, the external
 * client its server's own header.
 */
final class McpRpc {

    /**
     * Spring's {@code WebMvcStatelessServerTransport} rejects the request with an empty 400 unless
     * the Accept header contains both of these media types. It compares with
     * {@code MediaType.equals}, so quality parameters ({@code ;q=0.9}) break the match — this
     * string must stay exact. Other servers answer in either of the two.
     */
    static final String ACCEPT = "application/json, text/event-stream";

    /** The most of a reply that is read: a tool list is small, and a tool result is cut far below this. */
    static final long MAX_BODY = 4L << 20;

    private static final String EVENT_STREAM = "text/event-stream";

    private final String name;
    private final URI endpoint;
    private final HttpClient http;
    private final JsonMapper json;
    private final Duration timeout;
    private final AtomicLong requestIds = new AtomicLong();

    McpRpc(String name, URI endpoint, HttpClient http, JsonMapper json, Duration timeout) {
        this.name = name;
        this.endpoint = endpoint;
        this.http = http;
        this.json = json;
        this.timeout = timeout;
    }

    /** What the server answered, before any judgement of it. */
    record Reply(int status, HttpHeaders headers, String body) {
    }

    String name() {
        return name;
    }

    URI endpoint() {
        return endpoint;
    }

    long nextId() {
        return requestIds.incrementAndGet();
    }

    static Map<String, Object> request(long id, String method, Object params) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params);
        return message;
    }

    static Map<String, Object> notification(String method) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        return message;
    }

    /**
     * POSTs one JSON-RPC message. The status is the caller's to judge; only not getting an answer
     * throws: unreachable, interrupted, longer than the timeout (the whole reply, not just its
     * headers), or a body over {@link #MAX_BODY}.
     */
    Reply post(Map<String, String> headers, Object message) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(message), StandardCharsets.UTF_8));
        return send(request, headers);
    }

    /** A DELETE at the endpoint: how a Streamable HTTP session is ended. */
    Reply delete(Map<String, String> headers) {
        return send(HttpRequest.newBuilder(endpoint).DELETE(), headers);
    }

    private Reply send(HttpRequest.Builder request, Map<String, String> headers) {
        request.header("Accept", ACCEPT).timeout(timeout);
        headers.forEach(request::header);
        // sendAsync + get: HttpRequest's own timeout covers the headers only, and a server that
        // holds its event stream open would otherwise hold the caller with it.
        CompletableFuture<HttpResponse<String>> future = http.sendAsync(request.build(),
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), MAX_BODY));
        try {
            HttpResponse<String> response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Reply(response.statusCode(), response.headers(), response.body());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new McpException(name + " did not answer within " + timeout.toSeconds() + " s", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted calling " + name, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new McpException("Could not call " + name + " at " + endpoint + ": " + cause.getMessage(), cause);
        }
    }

    /**
     * The {@code result} of the request {@code id} in a 2xx reply, found in the one JSON message
     * or among the events of a stream; any other status, a JSON-RPC {@code error}, or a stream
     * that ends without the answer throws.
     */
    JsonNode result(Reply reply, long id) {
        if (reply.status() < 200 || reply.status() > 299) {
            throw failure(reply);
        }
        String contentType = reply.headers().firstValue("Content-Type").orElse("");
        JsonNode message = contentType.regionMatches(true, 0, EVENT_STREAM, 0, EVENT_STREAM.length())
                ? eventWithId(reply.body(), id)
                : json.readTree(reply.body());
        JsonNode error = message.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new McpException(error.path("message").asString("unknown JSON-RPC error"), false);
        }
        return message.path("result");
    }

    /** The exception a non-2xx reply is. */
    McpException failure(Reply reply) {
        int status = reply.status();
        if (status == 401 || status == 403) {
            return new McpException(name + " refused the credential (HTTP " + status + ")", true);
        }
        if (status == 429) {
            // datahub-api's RateLimitFilter says how many seconds are left of the window.
            Duration retryAfter = reply.headers().firstValue("Retry-After")
                    .filter(v -> v.matches("\\d{1,6}")).map(v -> Duration.ofSeconds(Long.parseLong(v)))
                    .orElse(null);
            return new McpException(name + " is rate limiting this caller"
                    + (retryAfter == null ? "" : "; retry after " + retryAfter.toSeconds() + " s"), retryAfter);
        }
        if (status >= 300 && status < 400) {
            return new McpException(name + " answered with a redirect (HTTP " + status + "), which is not followed", false);
        }
        String body = reply.body() == null ? "" : reply.body().strip();
        if (body.length() > 200) {
            body = body.substring(0, 200) + "…";
        }
        // A 400 with an empty body from the platform's servers almost always means the Accept header was wrong.
        return new McpException(name + " answered HTTP " + status + (body.isEmpty() ? "" : ": " + body), false);
    }

    /**
     * The JSON-RPC message with {@code id} among the events of a server-sent event stream: each
     * event's {@code data:} lines joined with a newline make one message; notifications and the
     * server's own requests (sampling, elicitation) carry another id or none and are passed over.
     */
    private JsonNode eventWithId(String stream, long id) {
        StringBuilder data = new StringBuilder();
        for (String line : stream.split("\r?\n", -1)) {
            if (line.isEmpty()) {
                JsonNode found = messageWithId(data, id);
                if (found != null) {
                    return found;
                }
                continue;
            }
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.startsWith("data: ") ? line.substring(6) : line.substring(5));
            }
            // event:, id:, retry: and comment lines say nothing about the message.
        }
        JsonNode found = messageWithId(data, id);
        if (found != null) {
            return found;
        }
        throw new McpException(name + " ended the stream without answering request " + id, false);
    }

    private JsonNode messageWithId(StringBuilder data, long id) {
        if (data.isEmpty()) {
            return null;
        }
        JsonNode message = json.readTree(data.toString());
        data.setLength(0);
        JsonNode messageId = message.path("id");
        boolean ours = (messageId.isNumber() && messageId.asLong() == id)
                || (messageId.isString() && messageId.asString().equals(Long.toString(id)));
        return ours ? message : null;
    }

    /** The tools of a {@code tools/list} result, with the hints the server stated. */
    List<LlmToolDef> tools(JsonNode result) {
        List<LlmToolDef> tools = new ArrayList<>();
        for (JsonNode tool : result.path("tools").values()) {
            JsonNode annotations = tool.path("annotations");
            tools.add(new LlmToolDef(
                    tool.path("name").asString(),
                    tool.path("description").asString(),
                    // Kept as a raw JSON string: both provider adapters pass JSON Schema through
                    // untouched, so there is nothing to model here.
                    json.writeValueAsString(tool.path("inputSchema")),
                    hint(annotations, "readOnlyHint"),
                    hint(annotations, "destructiveHint")));
        }
        return List.copyOf(tools);
    }

    /** The hint as the server stated it, or null: an absent hint is not a false one. */
    private static Boolean hint(JsonNode annotations, String field) {
        JsonNode value = annotations.path(field);
        return value.isBoolean() ? value.asBoolean() : null;
    }

    /** A {@code tools/call} result: its text blocks, joined, and whether the tool failed. */
    static McpToolResult toolResult(JsonNode result) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : result.path("content").values()) {
            if ("text".equals(block.path("type").asString())) {
                text.append(block.path("text").asString());
            }
        }
        return new McpToolResult(text.toString(), result.path("isError").asBoolean(false));
    }
}
