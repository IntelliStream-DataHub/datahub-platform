// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.validation.FieldValidationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Extension members have to land at the top level, beside {@code type} and {@code detail}.
 *
 * <p>Serializing a {@link ProblemDetail} with a plain Jackson mapper nests everything under a
 * {@code properties} object and emits {@code "instance": null} — the record's literal field layout.
 * Spring's mixin is what flattens it to the RFC 9457 shape, and it is applied by the message
 * converter, not by the type. So the only way to know what a caller receives is to render one
 * through MVC, which is what this does. Without it a reader would reasonably conclude from a unit
 * test that {@code fields} arrives nested, and write clients against {@code $.properties.fields}.
 *
 * <p>{@code FilterCursorRejectionTest} covers the standard members this way already; nothing
 * covered an extension, and the {@code fields} extension is the whole point of {@link Problems}.
 */
class ProblemWireShapeTest {

    @RestController
    static class Throwing {
        @GetMapping("/boom")
        ProblemDetail boom() {
            return Problems.fieldValidation(List.of(new FieldValidationError(
                    "source",
                    new String[] {"resource.source.max.length.error"},
                    new Object[] {129},
                    "Source max length is 128 characters.")));
        }
    }

    @Test
    @DisplayName("fields is a top-level member, not nested under properties")
    void extensionsRenderAtTheTopLevel() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Throwing()).build();

        mvc.perform(get("/boom").accept(MediaType.ALL))
                .andExpect(status().isBadRequest())  // ProblemDetail carries its own status
                .andExpect(jsonPath("$.type").value("https://intellistream.ai/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fields[0].field").value("source"))
                .andExpect(jsonPath("$.fields[0].code").value("resource.source.max.length.error"))
                .andExpect(jsonPath("$.fields[0].rejected").value(129))
                .andExpect(jsonPath("$.properties").doesNotExist())
                // Spring fills instance with the request path. Left in rather than suppressed: RFC
                // 9457 defines it as the occurrence this problem refers to, and "which request"
                // is the first thing anyone asks of an error in a log.
                .andExpect(jsonPath("$.instance").value("/boom"));
    }
}
