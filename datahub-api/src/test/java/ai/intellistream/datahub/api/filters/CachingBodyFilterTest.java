// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A failure below the body cache must reach the container. Swallowed, it left the status unset and
 * the caller saw {@code 200} with an empty body for a request the api never finished, which is how a
 * datapoint insert that failed on the server was reported as stored.
 */
class CachingBodyFilterTest {

    /** Both branches the filter takes: wrapped, and streaming. */
    @ParameterizedTest
    @CsvSource({
            "POST, /timeseries/data",
            "PUT,  /files",
    })
    void aFailureDownTheChainPropagates(String method, String uri) {
        // What DispatcherServlet raises for anything no handler answered, an Error included.
        ServletException failure = new ServletException("Handler dispatch failed",
                new OutOfMemoryError("Java heap space"));
        FilterChain chain = (req, res) -> {
            throw failure;
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> new CachingBodyFilter()
                .doFilter(new MockHttpServletRequest(method, uri), response, chain))
                .isSameAs(failure);
        assertThat(response.isCommitted())
                .as("left uncommitted, so the container can still answer with a 500")
                .isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /timeseries/data",
            "PUT,  /files",
    })
    void anIoFailureDownTheChainPropagates(String method, String uri) {
        IOException failure = new IOException("connection reset");
        FilterChain chain = (req, res) -> {
            throw failure;
        };

        assertThatThrownBy(() -> new CachingBodyFilter()
                .doFilter(new MockHttpServletRequest(method, uri), new MockHttpServletResponse(), chain))
                .isSameAs(failure);
    }

    @Test
    void aSuccessfulResponseIsWrappedAndItsBodyReachesTheClient() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            assertThat(req).isInstanceOf(ContentCachingRequestWrapper.class);
            assertThat(res).isInstanceOf(ContentCachingResponseWrapper.class);
            res.getOutputStream().write("{\"items\":[]}".getBytes(StandardCharsets.UTF_8));
        };

        new CachingBodyFilter().doFilter(new MockHttpServletRequest("POST", "/timeseries/data/list"), response, chain);

        assertThat(response.getContentAsString()).isEqualTo("{\"items\":[]}");
    }
}
