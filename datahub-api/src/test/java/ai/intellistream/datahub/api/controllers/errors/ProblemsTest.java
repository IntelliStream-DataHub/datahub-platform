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
    @DisplayName("a constraint violation keeps its key but not the value that was sent")
    void constraintViolationKeepsKeyButNotValue() {
        ProblemDetail problem = Problems.constraintViolation(violate());

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getType()).isEqualTo(Problems.CONSTRAINT_VIOLATION);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) problem.getProperties().get("fields");
        assertThat(fields).hasSize(1);
        assertThat(fields.getFirst())
                .containsEntry("field", "name")
                .containsEntry("message", "too long")
                // The value can be a credential or a whole object; the caller already has it.
                .doesNotContainKey("rejected");
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
        ObjectError error = new FieldError("form", "apiKey", "sk-live-secret", false,
                new String[] {"Size"}, null, "must be at least 3 characters");

        ProblemDetail problem = Problems.bindingFailure(List.of(error));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) problem.getProperties().get("fields");
        assertThat(fields.getFirst())
                .containsEntry("field", "apiKey")
                .containsEntry("message", "must be at least 3 characters")
                .doesNotContainKey("rejected");
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

    @Test
    @DisplayName("a bare status gets its own type, and a 500 never carries the detail it was given")
    void forStatusNamesTheStatusAndKeepsInternalsOut() {
        assertThat(Problems.forStatus(405, null).getType()).isEqualTo(Problems.METHOD_NOT_ALLOWED);
        assertThat(Problems.forStatus(415, "Content-Type 'text/plain' is not supported.").getDetail())
                .isEqualTo("Content-Type 'text/plain' is not supported.");

        ProblemDetail internal = Problems.forStatus(500, "Connection refused: db.internal:5432");
        assertThat(internal.getType()).isEqualTo(Problems.INTERNAL);
        assertThat(internal.getDetail()).isEqualTo(Problems.INTERNAL_DETAIL);
    }

    @Test
    @DisplayName("a disabled feature is a 403 that names the feature")
    void featureDisabledNamesTheFeature() {
        ProblemDetail problem = Problems.featureDisabled("files", "Files feature is not enabled for this organization.");

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(problem.getType()).isEqualTo(Problems.FEATURE_DISABLED);
        assertThat(problem.getProperties()).containsEntry("feature", "files");
    }


    @Test
    @DisplayName("decorate adds the request id and a retry verdict, without overriding either")
    void decorateAddsRequestIdAndRetry() {
        ProblemDetail problem = Problems.decorate(Problems.notFound("gone"), "01J-request");

        assertThat(problem.getProperties())
                .containsEntry("requestId", "01J-request")
                .containsEntry("retry", Problems.RETRY_CHANGE_REQUEST);

        ProblemDetail preset = Problems.notFound("gone");
        preset.setProperty("retry", Problems.RETRY_SAME_REQUEST);
        assertThat(Problems.decorate(preset, null).getProperties())
                .containsEntry("retry", Problems.RETRY_SAME_REQUEST)
                .doesNotContainKey("requestId");
    }

    @Test
    @DisplayName("retry tells waiting apart from changing the request and from needing an operator")
    void retryVerdicts() {
        assertThat(Problems.retryFor(Problems.conflict(Problems.OPTIMISTIC_LOCK, "lost a race")))
                .isEqualTo(Problems.RETRY_SAME_REQUEST);
        assertThat(Problems.retryFor(Problems.duplicate("taken", List.of())))
                .isEqualTo(Problems.RETRY_CHANGE_REQUEST);
        assertThat(Problems.retryFor(Problems.featureDisabled("files", "off")))
                .isEqualTo(Problems.RETRY_NEEDS_OPERATOR);
        assertThat(Problems.retryFor(Problems.forStatus(429, null))).isEqualTo(Problems.RETRY_SAME_REQUEST);
        assertThat(Problems.retryFor(Problems.forStatus(401, null))).isEqualTo(Problems.RETRY_CHANGE_REQUEST);
        assertThat(Problems.retryFor(Problems.forStatus(403, null))).isEqualTo(Problems.RETRY_NEEDS_OPERATOR);
        assertThat(Problems.retryFor(Problems.forStatus(500, null))).isEqualTo(Problems.RETRY_NEEDS_OPERATOR);
        assertThat(Problems.retryFor(Problems.restoreRefused("folder-missing", "gone")))
                .isEqualTo(Problems.RETRY_CHANGE_REQUEST);
    }

}
