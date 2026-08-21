// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Collection;
import java.util.List;

public class AllowedAggregatesValidator implements ConstraintValidator<AllowedAggregates, Collection<String>> {

    private static final List<String> allowedValues = List.of("avg", "sum", "min", "max");

    @Override
    public boolean isValid(Collection<String> values, ConstraintValidatorContext context) {
        if (values == null || values.isEmpty()) {
            return true; // The collection can be null or empty
        }

        return values.stream().allMatch(allowedValues::contains); // Checks if all values are allowed
    }

}
