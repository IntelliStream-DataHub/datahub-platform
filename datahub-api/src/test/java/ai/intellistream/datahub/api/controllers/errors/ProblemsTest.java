// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.validation.FieldValidationError;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one error shape, and what it must keep carrying.
 *
 * <p>The point of the {@code fields} extension is that it does not lose what the old shape lost.
 * {@code BuildErrorResponse} collapsed every failure into {@code Map.of(path, message)}, so the
 * i18n key and the offending value were computed and then dropped — a caller got English prose and
 * could neither localise it nor read the limit programmatically.
 */
class ProblemsTest {

    private record Bounded(@Size(max = 3, message = "too long") String name) {}

    private static Set<ConstraintViolation<Bounded>> violate() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        return validator.validate(new Bounded("far too long"));
    }

    @Test
    @DisplayName("every type is one host and one scheme")
    void typesShareOneScheme() {
        assertThat(Problems.BASE).isEqualTo("https://intellistream.ai/errors/");
        assertThat(List.of(Problems.VALIDATION_FAILED, Problems.DUPLICATE,
                        Problems.CONFLICT, Problems.CONSTRAINT_VIOLATION))
                .allSatisfy(uri -> assertThat(uri.toString()).startsWith(Problems.BASE));
        assertThat(Problems.type("some-new-thing").toString())
                .isEqualTo("https://intellistream.ai/errors/some-new-thing");
    }

    @Test
    @DisplayName("a constraint violation keeps its key and its rejected value")
    void constraintViolationKeepsKeyAndValue() {
        ProblemDetail problem = Problems.constraintViolation(violate());

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getType()).isEqualTo(Problems.CONSTRAINT_VIOLATION);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) problem.getProperties().get("fields");
        assertThat(fields).hasSize(1);
        assertThat(fields.getFirst())
                .containsEntry("field", "name")
                .containsEntry("message", "too long")
                .containsEntry("rejected", "far too long");
        // The template is the key before interpolation — what a caller localises on.
        assertThat(fields.getFirst().get("code")).isEqualTo("too long");
    }

    @Test
    @DisplayName("a hand-written validator's i18n key survives, and its argument with it")
    void fieldValidationKeepsTheI18nKey() {
        FieldValidationError error = new FieldValidationError(
                "Resource",
                new String[] {"resource.source.max.length.error", "resource.source.error"},
                new Object[] {129},
                "Source max length is 128 characters.");

        ProblemDetail problem = Problems.fieldValidation(List.of(error));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) problem.getProperties().get("fields");
        assertThat(fields.getFirst())
                .containsEntry("field", "Resource")
                .containsEntry("code", "resource.source.max.length.error")
                .containsEntry("rejected", 129)
                .containsEntry("message", "Source max length is 128 characters.");
    }

    @Test
    @DisplayName("a binding failure reads the same as a service-side one")
    void bindingFailureMatchesTheServiceShape() {
        ObjectError error = new FieldError("form", "externalId", "", false,
                new String[] {"Size"}, null, "must be at least 3 characters");

        ProblemDetail problem = Problems.bindingFailure(List.of(error));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) problem.getProperties().get("fields");
        assertThat(fields.getFirst())
                .containsEntry("field", "externalId")
                .containsEntry("message", "must be at least 3 characters");
    }

    @Test
    @DisplayName("nothing to report means no empty extension member")
    void noFieldsMeansNoMember() {
        ProblemDetail problem = Problems.of(HttpStatus.CONFLICT, Problems.CONFLICT, "Conflict", "lost a race");

        assertThat(Problems.withFields(problem, List.of()).getProperties()).isNull();
    }

    @Test
    @DisplayName("a duplicate names what collided")
    void duplicateNamesTheCollision() {
        ProblemDetail problem = Problems.duplicate("External id already exists.",
                List.of(Map.of("externalId", "pump_7")));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getType()).isEqualTo(Problems.DUPLICATE);
        assertThat(problem.getProperties().get("duplicated"))
                .isEqualTo(List.of(Map.of("externalId", "pump_7")));
    }
}
