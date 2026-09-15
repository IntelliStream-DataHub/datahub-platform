// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.filters;

import jakarta.servlet.FilterChain;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Body caching must not change what the caller is told happened.
 *
 * <p>The filter used to log a failure below it and return, which left the response at Tomcat's
 * default 200 with an empty body — a request the server dropped, reported to the client as a
 * success. DispatcherServlet wraps everything a handler throws into {@code ServletException}, so
 * that applied to every unhandled failure on every non-streaming endpoint.
 */
class CachingBodyFilterTest {

    private final CachingBodyFilter filter = new CachingBodyFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private static MockHttpServletRequest post(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContent("{\"items\":[]}".getBytes(StandardCharsets.UTF_8));
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        return request;
    }

    @Test
    void aFailureBelowTheFilterIsNotReportedAsSuccess() {
        FilterChain exploding = (req, res) -> {
            throw new ServletException("Handler dispatch failed", new OutOfMemoryError("Java heap space"));
        };

        assertThatThrownBy(() -> filter.doFilter(post("/timeseries/data"), response, exploding))
                .isInstanceOf(ServletException.class)
                .hasMessage("Handler dispatch failed");

        // Left uncommitted, so the container is still free to turn this into a 500.
        assertThat(response.isCommitted()).isFalse();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void anIoFailureBelowTheFilterAlsoPropagates() {
        FilterChain exploding = (req, res) -> {
            throw new IOException("broken pipe");
        };

        assertThatThrownBy(() -> filter.doFilter(post("/events/create"), response, exploding))
                .isInstanceOf(IOException.class);
    }

    @Test
    void aFailureOnAStreamingEndpointAlsoPropagates() {
        FilterChain exploding = (req, res) -> {
            throw new ServletException("Handler dispatch failed");
        };

        assertThatThrownBy(() -> filter.doFilter(post("/resources/import"), response, exploding))
                .isInstanceOf(ServletException.class);
    }

    @Test
    void aBodyWrittenBeforeAFailureStillReachesTheCaller() throws Exception {
        FilterChain writesThenFails = (req, res) -> {
            res.getWriter().write("{\"error\":\"boom\"}");
            throw new ServletException("Handler dispatch failed");
        };

        assertThatThrownBy(() -> filter.doFilter(post("/events/create"), response, writesThenFails))
                .isInstanceOf(ServletException.class);

        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"boom\"}");
    }

    @Test
    void aSuccessfulResponseIsCopiedThroughExactlyOnce() throws Exception {
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws IOException, ServletException {
                res.getWriter().write("{\"items\":[]}");
                super.doFilter(req, res);
            }
        };

        filter.doFilter(post("/events/create"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getContentAsString()).isEqualTo("{\"items\":[]}");
    }
}
