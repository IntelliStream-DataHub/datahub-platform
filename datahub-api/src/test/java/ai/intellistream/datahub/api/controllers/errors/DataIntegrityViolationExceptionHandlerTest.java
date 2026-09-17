// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataIntegrityViolationExceptionHandlerTest {

    private final DataIntegrityViolationExceptionHandler handler = new DataIntegrityViolationExceptionHandler();

    /** duplicated is field -> value everywhere else; a constraint knows the field and not the value. */
    @Test
    void aMappedConstraintNamesTheFieldWithoutPretendingToKnowTheValue() {
        ProblemDetail problem = handler.handle(violation("label_hash_key"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getType()).isEqualTo(Problems.DUPLICATE);
        assertThat(problem.getProperties()).doesNotContainKey("duplicated");
        assertThat(problem.getProperties().get("fields")).isEqualTo(List.of(
                Map.of("field", "name", "message", "Label with same name already exists.")));
    }

    /** A file upload that lands on a taken path or external id answers its documented 409 with the field. */
    @Test
    void aTakenFilePathNamesPath() {
        ProblemDetail problem = handler.handle(violation("inodes_path_hash_active_uk"));

        assertThat(problem.getType()).isEqualTo(Problems.DUPLICATE);
        assertThat(problem.getProperties().get("fields")).isEqualTo(List.of(
                Map.of("field", "path", "message", "A file or folder already exists at this path.")));
    }

    @Test
    void anUnmappedConstraintIsStillAConflictWithNoFields() {
        ProblemDetail problem = handler.handle(violation("some_new_key"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getProperties()).isNull();
    }

    private static DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("duplicate key",
                new ConstraintViolationException("duplicate key", new SQLException("duplicate key"), constraint));
    }
}
