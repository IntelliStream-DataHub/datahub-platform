// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import ai.intellistream.datahub.api.binary.DatapointValueType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class AllowedValueTypeValidator implements ConstraintValidator<AllowedValueType, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Null/blank is owned by @NotBlank; don't add a second, confusing error for it.
        if (value == null || value.isBlank()) {
            return true;
        }
        return DatapointValueType.fromNameOrNull(value) != null;
    }
}
