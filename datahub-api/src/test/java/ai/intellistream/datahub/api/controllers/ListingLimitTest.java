// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.Problems;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ListingLimitTest {

    @Test
    void anAcceptableLimitIsNotRejected() {
        assertThat(ListingLimit.rejection(null)).isNull();
        assertThat(ListingLimit.rejection(10_000)).isNull();
    }

    /** The same fields entry a {@code @Max} failure on POST /filter produces, so both read alike. */
    @Test
    void anOverLimitIsAValidationProblemNamingTheField() {
        ProblemDetail problem = ListingLimit.rejection(10_001);

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getType()).isEqualTo(Problems.VALIDATION_FAILED);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) problem.getProperties().get("fields");
        assertThat(fields).singleElement().satisfies(field -> {
            assertThat(field).containsEntry("field", "limit").containsEntry("code", "Max");
            assertThat((String) field.get("message")).contains("10000");
        });
    }
}
