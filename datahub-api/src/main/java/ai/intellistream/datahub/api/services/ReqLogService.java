// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClientException;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class ReqLogService {

    /** What stands in for a body the log does not read: a file, a datapoint frame, a download. */
    private static final String BINARY_BODY = "binary";

    private final JsonMapper jsonMapper;
    private final Producer<String> httpMessageProducer;
    private final Environment environment;

    public ReqLogService(JsonMapper jsonMapper,
                         Producer<String> httpMessageProducer,
                         Environment environment
    ) {
        this.jsonMapper = jsonMapper;
        this.httpMessageProducer = httpMessageProducer;
        this.environment = environment;
    }

    @Async
    public void send(HttpServletRequest req, HttpServletResponse res) {
        if(req.getHeader("X-Request-Id") != null){
            Map<String, Object> data = new HashMap<>();
            data.put("id", req.getHeader("X-Request-Id"));
            data.put("request", serializeRequest(req));
            data.put("response", serializeResponse(req, res));
            try{
                httpMessageProducer.sendAsync(jsonMapper.writeValueAsString(data));
                res.setHeader("X-Request-Id", req.getHeader("X-Request-Id"));
            } catch (JacksonIOException e) {
                log.error(e.getMessage(), e);
            }
        } else {
            String[] activeProfiles = environment.getActiveProfiles();
            if(activeProfiles.length > 0 && activeProfiles[0].equals("dev")){
                log.debug(req.getMethod() + " " + req.getRequestURI());
                if(isDocsEndpoint(req.getRequestURI())){
                    return;
                }
                Enumeration<String> headerNames = req.getHeaderNames();
                while (headerNames.hasMoreElements()) {
                    String headerName = headerNames.nextElement();
                    log.debug("Header Name: " + headerName + ", Value: " + req.getHeader(headerName));
                }
                if (req instanceof ContentCachingRequestWrapper reqWrapper) {
                    String payload = BINARY_BODY;
                    if (hasTextualBody(req)) {
                        payload = new String(reqWrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
                        payload = payload.substring(0, Math.min(payload.length(), 512));
                    }
                    log.debug("Request body: {}", payload);
                }
                if (res instanceof ContentCachingResponseWrapper responseWrapper) {
                    String payload = BINARY_BODY;
                    if (!req.getRequestURI().contains("/files/download/")) {
                        payload = new String(responseWrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
                    }
                    log.debug("Response status: {} body: {}", responseWrapper.getStatus(), payload);
                }
            }
        }
    }

    /**
     * The documentation surface: Redoc's bundle and assets, the generated OpenAPI document, and
     * Swagger UI. Everything it serves is large and static — the OpenAPI document alone runs to a
     * few hundred KB — so logging the bodies buries whatever request is actually being debugged.
     * The request line is still logged; only the headers and payloads are skipped.
     */
    private static boolean isDocsEndpoint(String uri) {
        return uri.startsWith("/static/redoc")
                || uri.startsWith("/api-docs")
                || uri.startsWith("/swagger-ui");
    }

    private Map<String, Object> serializeResponse(HttpServletRequest req, HttpServletResponse res) {
        // Create a map to hold the response data
        Map<String, Object> responseMap = new HashMap<>();

        // Status and headers are logged for every response, including the streaming file endpoints
        // (download/upload) whose responses are NOT wrapped in a ContentCachingResponseWrapper
        // (the wrapper buffers the whole body in memory and can't carry a >2 GB Content-Length).
        // We never log the file binary itself, only the HTTP metadata.
        responseMap.put("status", res.getStatus());

        Map<String, String> headersMap = new HashMap<>();
        for (String headerName : res.getHeaderNames()) {
            headersMap.put(headerName, res.getHeader(headerName));
        }
        responseMap.put("headers", headersMap);

        String payload = BINARY_BODY;
        if (res instanceof ContentCachingResponseWrapper responseWrapper
                && !req.getRequestURI().contains("/files/download/")) {
            payload = new String(responseWrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
        }
        responseMap.put("payload", payload);
        return responseMap;
    }

    public Map<String, Object> serializeRequest(HttpServletRequest request) {
        // Create a map to hold the request data
        Map<String, Object> requestMap = new HashMap<>();

        // Add HTTP method
        requestMap.put("method", request.getMethod());

        // Add headers
        Map<String, String> headersMap = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headersMap.put(headerName, request.getHeader(headerName));
        }
        requestMap.put("headers", headersMap);

        // Add request body. A non-textual one is never read, not even to measure it: the bytes are
        // a file or a datapoint frame, they can exceed a Pulsar message on their own, and this
        // envelope is republished on the http topic.
        if (request instanceof ContentCachingRequestWrapper reqWrapper) {
            String payload = BINARY_BODY;
            if (hasTextualBody(request)) {
                payload = new String(reqWrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
            }
            requestMap.put("body", payload);
        }

        return requestMap;
    }

    /**
     * Only a JSON, XML, form or text body is worth stringifying into the request log; a binary one
     * (a file upload, a datapoint frame) would be garbage there and can be larger than a Pulsar
     * message.
     */
    public static boolean hasTextualBody(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        String type = contentType.toLowerCase(java.util.Locale.ROOT);
        return type.startsWith("application/json")
                || type.startsWith("application/xml")
                || type.startsWith("application/x-www-form-urlencoded")
                || type.startsWith("text/");
    }
}
