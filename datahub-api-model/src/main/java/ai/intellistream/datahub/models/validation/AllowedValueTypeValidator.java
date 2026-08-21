// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

public class AllowedValueTypeValidator implements ConstraintValidator<AllowedValueType, String> {

    // Canonical value-type names. The id / ClickHouse-table mapping lives in TimeseriesValueType
    // (datahub-infra), which this module must not depend on — keep this list in sync with it.
    private static final Set<String> ALLOWED = Set.of(
            "bigint", "float", "float32", "numeric", "decimal32", "mixed", "text");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Null/blank is owned by @NotBlank; don't add a second, confusing error for it.
        if (value == null || value.isBlank()) {
            return true;
        }
        return ALLOWED.contains(value.toLowerCase());
    }
}
