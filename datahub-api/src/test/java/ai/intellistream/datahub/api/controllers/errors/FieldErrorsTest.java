// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

import ai.intellistream.datahub.validation.FieldValidationError;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A validator error keeps its i18n code and its bound all the way to the wire.
 *
 * <p>{@code NodeUpdateService.validateOrThrow} used to flatten each error to
 * {@code (objectName, defaultMessage)} on its way into a {@link BadRequestException}, so a resource
 * or policy update answered with English prose a caller could neither localise nor read the broken
 * limit from — the exact loss {@code Problems} was written to end, still happening at the one call
 * site that never moved over. These pin the shape rather than the call site, so the guarantee holds
 * wherever a hand-written validator's errors are accumulated.
 */
class FieldErrorsTest {

    private static final FieldValidationError TOO_LONG = new FieldValidationError(
            "source",
            new String[]{"resource.source.max.length.error", "max.length.error"},
            new Object[]{255},
            "Source must be at most 255 characters.");

    @Test
    @DisplayName("An accumulated validator error keeps its code and its bound")
    void theCodeAndBoundSurvive() {
        List<Problems.FieldProblem> fields =
                new FieldErrors().addFieldError(TOO_LONG).asList();

        assertThat(fields).singleElement().satisfies(field -> {
            assertThat(field.field()).isEqualTo("source");
            assertThat(field.message()).isEqualTo("Source must be at most 255 characters.");
            // The specific code, not the more general fallbacks behind it.
            assertThat(field.code()).isEqualTo("resource.source.max.length.error");
            assertThat(field.rejected()).isEqualTo(255);
        });
    }

    @Test
    @DisplayName("Accumulating and rendering straight to a problem agree, member for member")
    void bothPathsProduceTheSameField() {
        Problems.FieldProblem accumulated =
                new FieldErrors().addFieldError(TOO_LONG).asList().getFirst();

        // withFields renders each entry for the wire, so compare members rather than identity.
        Object rendered = Problems.fieldValidation(List.of(TOO_LONG)).getProperties().get("fields");

        assertThat(rendered).asInstanceOf(InstanceOfAssertFactories.LIST).singleElement()
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("field", accumulated.field())
                .containsEntry("message", accumulated.message())
                .containsEntry("code", accumulated.code())
                .containsEntry("rejected", accumulated.rejected());
    }

    @Test
    @DisplayName("The (field, message) overload still works for throw sites with nothing more")
    void theTerseOverloadIsUnchanged() {
        List<Problems.FieldProblem> fields =
                new FieldErrors().addFieldError("externalId", "must not be blank").asList();

        assertThat(fields).singleElement().satisfies(field -> {
            assertThat(field.field()).isEqualTo("externalId");
            assertThat(field.message()).isEqualTo("must not be blank");
            assertThat(field.code()).isNull();
            assertThat(field.rejected()).isNull();
        });
    }
}
