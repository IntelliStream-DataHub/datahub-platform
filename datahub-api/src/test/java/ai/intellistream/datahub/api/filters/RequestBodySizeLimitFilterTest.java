// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.filters;

import ai.intellistream.datahub.api.config.LimitsProperties;
import ai.intellistream.datahub.api.services.IngestQuotaService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The application's own body ceiling. nginx caps bodies too, but the api is reachable directly on
 * its service port, so the limit has to exist here or it does not exist at all.
 */
class RequestBodySizeLimitFilterTest {

    private final LimitsProperties limits = new LimitsProperties();
    private final RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(limits, mock(IngestQuotaService.class));
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    private static MockHttpServletRequest post(String uri, int bodyBytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContent("x".repeat(bodyBytes).getBytes(StandardCharsets.UTF_8));
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        return request;
    }

    @Test
    void bodyUnderTheLimitPassesThrough() throws Exception {
        limits.setMaxBodyBytes(1024);
        filter.doFilter(post("/events/create", 512), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void declaredContentLengthOverTheLimitIsRejectedBeforeTheBodyIsRead() throws Exception {
        limits.setMaxBodyBytes(1024);
        filter.doFilter(post("/events/create", 2048), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("request-too-large", "\"limitBytes\":1024");
        assertThat(chain.getRequest()).as("the chain must not run").isNull();
    }

    /** A chunked request: bytes arrive, but no {@code Content-Length} declares how many. */
    private static MockHttpServletRequest chunkedPost(String uri, int bodyBytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent("x".repeat(bodyBytes).getBytes(StandardCharsets.UTF_8));
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.addHeader("Transfer-Encoding", "chunked");
        return request;
    }

    /** A chain that actually consumes the body, which is what drives the counting stream. */
    private static MockFilterChain readingChain() {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws IOException, ServletException {
                req.getInputStream().readAllBytes();
                super.doFilter(req, res);
            }
        };
    }

    @Test
    void undeclaredLengthUnderTheLimitReadsThrough() throws Exception {
        limits.setMaxBodyBytes(4096);
        MockFilterChain chain = readingChain();

        filter.doFilter(chunkedPost("/events/create", 512), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void undeclaredLengthOverTheLimitFailsMidRead() {
        // Nothing declared the size, so the cap can only be applied while the bytes are consumed.
        limits.setMaxBodyBytes(64);

        assertThatThrownBy(() -> filter.doFilter(chunkedPost("/events/create", 4096), response, readingChain()))
                .isInstanceOf(RequestBodySizeLimitFilter.RequestBodyTooLargeException.class)
                .hasMessageContaining("64");
    }

    @Test
    void datapointInsertsGetTheirOwnLargerCeiling() throws Exception {
        limits.setMaxBodyBytes(1024);
        limits.setMaxBodyBytesDatapoints(8192);

        filter.doFilter(post("/timeseries/data", 4096), response, chain);

        assertThat(response.getStatus())
                .as("4 KB is over the general cap but under the datapoint cap")
                .isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void fileUploadIsExemptSoLargeUploadsStillStream() throws Exception {
        limits.setMaxBodyBytes(64);
        MockHttpServletRequest upload = new MockHttpServletRequest("PUT", "/files");
        upload.setContent("x".repeat(4096).getBytes(StandardCharsets.UTF_8));

        filter.doFilter(upload, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void fileDownloadIsExempt() throws Exception {
        limits.setMaxBodyBytes(64);
        MockHttpServletRequest download = new MockHttpServletRequest("GET", "/files/download/abc");
        download.setContent("x".repeat(4096).getBytes(StandardCharsets.UTF_8));

        filter.doFilter(download, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void graphImportIsExemptSoLargeFilesStillStream() throws Exception {
        limits.setMaxBodyBytes(64);
        MockHttpServletRequest upload = new MockHttpServletRequest("POST", "/resources/import");
        upload.setContent("x".repeat(4096).getBytes(StandardCharsets.UTF_8));
        upload.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);

        filter.doFilter(upload, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void graphExportIsExempt() throws Exception {
        limits.setMaxBodyBytes(64);
        MockHttpServletRequest export = new MockHttpServletRequest("GET", "/resources/export/42");
        export.setContent("x".repeat(4096).getBytes(StandardCharsets.UTF_8));

        filter.doFilter(export, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void aZeroLimitDisablesTheCheck() throws Exception {
        limits.setMaxBodyBytes(0);
        filter.doFilter(post("/events/create", 8192), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
    }
}
