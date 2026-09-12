// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import org.apache.pulsar.client.api.Producer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.web.util.ContentCachingRequestWrapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The request log republishes its envelope on the http topic, so a body it cannot render as text
 * must not be read at all: a file or a datapoint frame would be mojibake there and can exceed a
 * Pulsar message on its own.
 */
class ReqLogServiceTest {

    @SuppressWarnings("unchecked")
    private final ReqLogService service = new ReqLogService(
            JsonMapper.builder().build(), mock(Producer.class), mock(Environment.class));

    private static ContentCachingRequestWrapper request(String contentType) {
        ContentCachingRequestWrapper request = mock(ContentCachingRequestWrapper.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(request.getContentType()).thenReturn(contentType);
        return request;
    }

    @Test
    @DisplayName("A datapoint frame is logged as \"binary\" and its bytes are never touched")
    void binaryBodyIsNotRead() {
        ContentCachingRequestWrapper frame = request("application/vnd.intellistream.datapoint-block");

        Map<String, Object> logged = service.serializeRequest(frame);

        assertThat(logged.get("body")).isEqualTo("binary");
        verify(frame, never()).getContentAsByteArray();
    }

    @Test
    @DisplayName("A file upload is logged as \"binary\" too")
    void octetStreamIsNotRead() {
        ContentCachingRequestWrapper upload = request("application/octet-stream");

        assertThat(service.serializeRequest(upload).get("body")).isEqualTo("binary");
        verify(upload, never()).getContentAsByteArray();
    }

    @Test
    @DisplayName("A request with no content type is treated as binary rather than guessed at")
    void missingContentTypeIsNotRead() {
        ContentCachingRequestWrapper unknown = request(null);

        assertThat(service.serializeRequest(unknown).get("body")).isEqualTo("binary");
        verify(unknown, never()).getContentAsByteArray();
    }

    @Test
    @DisplayName("A JSON body is still logged in full")
    void textualBodyIsRead() {
        ContentCachingRequestWrapper json = request("application/json;charset=UTF-8");
        when(json.getContentAsByteArray()).thenReturn("{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        assertThat(service.serializeRequest(json).get("body")).isEqualTo("{\"a\":1}");
    }
}
