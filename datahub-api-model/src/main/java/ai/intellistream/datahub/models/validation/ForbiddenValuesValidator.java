// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class ForbiddenValuesValidator implements ConstraintValidator<ForbiddenValues, String> {

    private Set<String> forbiddenValuesSet;
    private final Set<String> forbiddenValuesCollection =
            Set.of("object", "timeseries", "all_datapoints", "function", "asset",
                    "etl", "admin", "superuser", "datahub", "datahub_public",
                    "intellistream", "event", "events", "datapoints", "datapoint");

    @Override
    public void initialize(ForbiddenValues constraintAnnotation) {
        forbiddenValuesSet = new HashSet<>(Arrays.asList(constraintAnnotation.forbiddenValues()));
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // Consider null values as valid. Adjust this as needed.
        }
        return !forbiddenValuesSet.contains(value.toLowerCase()) || forbiddenValuesCollection.contains(value.toLowerCase());
    }
}
