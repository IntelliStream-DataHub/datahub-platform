// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.filters;

import ai.intellistream.datahub.api.binary.FrameLimits;
import ai.intellistream.datahub.api.config.LimitsProperties;
import ai.intellistream.datahub.api.services.IngestQuotaService;
import ai.intellistream.datahub.api.services.ReqLogService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * How the request filters treat {@code POST /timeseries/data/binary}: capped by its own limit and
 * charged to the bytes quota like any write, never buffered by the body cache, and never
 * stringified into the request log.
 */
class BinaryDatapointRequestFiltersTest {

    private static MockHttpServletRequest binaryPost(int bodyBytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/timeseries/data/binary");
        request.setContent(new byte[bodyBytes]);
        request.setContentType(FrameLimits.MEDIA_TYPE);
        return request;
    }

    @Test
    void theBinaryPathHasItsOwnCapAndIsChargedForItsBytes() throws Exception {
        LimitsProperties limits = new LimitsProperties();
        limits.setMaxBodyBytesDatapoints(100);
        limits.setMaxBodyBytesDatapointsBinary(1024);
        IngestQuotaService quota = mock(IngestQuotaService.class);
        RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(limits, quota);

        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(binaryPost(512), response, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(quota).checkAndRecord(IngestQuotaService.QuotaMetric.BYTES, 512);

        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter.doFilter(binaryPost(2048), refused, new MockFilterChain());
        assertThat(refused.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(refused.getContentAsString()).contains("\"limitBytes\":1024");
    }

    @Test
    void theBinaryPathIsNotAStreamingEndpointButSkipsTheBodyCache() {
        MockHttpServletRequest post = binaryPost(1);
        assertThat(StreamingEndpoints.matches(post)).isFalse();
        assertThat(StreamingEndpoints.isBinaryDatapointInsert(post)).isTrue();

        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/timeseries/data/binary");
        assertThat(StreamingEndpoints.isBinaryDatapointInsert(get)).isFalse();

        MockHttpServletRequest json = new MockHttpServletRequest("POST", "/timeseries/data");
        assertThat(StreamingEndpoints.isBinaryDatapointInsert(json)).isFalse();
    }

    @Test
    void onlyTextualBodiesReachTheRequestLog() {
        MockHttpServletRequest binary = binaryPost(1);
        assertThat(ReqLogService.hasTextualBody(binary)).isFalse();

        MockHttpServletRequest json = new MockHttpServletRequest("POST", "/timeseries/data");
        json.setContentType(MediaType.APPLICATION_JSON_VALUE);
        assertThat(ReqLogService.hasTextualBody(json)).isTrue();

        MockHttpServletRequest form = new MockHttpServletRequest("POST", "/x");
        form.setContentType("application/x-www-form-urlencoded; charset=UTF-8");
        assertThat(ReqLogService.hasTextualBody(form)).isTrue();

        MockHttpServletRequest none = new MockHttpServletRequest("POST", "/x");
        assertThat(ReqLogService.hasTextualBody(none)).isFalse();

        MockHttpServletRequest octet = new MockHttpServletRequest("PUT", "/files");
        octet.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        assertThat(ReqLogService.hasTextualBody(octet)).isFalse();
    }
}
