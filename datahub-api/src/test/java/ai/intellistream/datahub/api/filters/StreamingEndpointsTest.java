// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.filters;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** The one list both the body cache and the body-size cap consult, so it is pinned here. */
class StreamingEndpointsTest {

    @ParameterizedTest
    @CsvSource({
            "PUT,  /files,                    true",
            "PUT,  /files/,                   true",
            "POST, /files,                    false",
            "GET,  /files/download/abc,       true",
            "GET,  /files/list,               false",
            "GET,  /resources/export/42,      true",
            "POST, /resources/export/42,      false",
            "POST, /resources/import,         true",
            "POST, /resources/import/,        true",
            "GET,  /resources/import,         false",
            "POST, /resources/importer,       false",
            "POST, /resources/create,         false",
    })
    void matchesOnlyTheStreamingEndpoints(String method, String uri, boolean expected) {
        assertThat(StreamingEndpoints.matches(new MockHttpServletRequest(method, uri))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/resources/import,    true",
            "GET,  /api/resources/export/7,  true",
            "PUT,  /api/files,               true",
    })
    void stripsTheContextPathFirst(String method, String uri, boolean expected) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContextPath("/api");
        assertThat(StreamingEndpoints.matches(request)).isEqualTo(expected);
    }
}
