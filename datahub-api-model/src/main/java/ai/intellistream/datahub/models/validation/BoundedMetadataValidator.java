// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Map;

public class BoundedMetadataValidator implements ConstraintValidator<BoundedMetadata, Map<String, String>> {

    private int maxEntries;
    private int maxKeyLength;
    private int maxValueLength;

    @Override
    public void initialize(BoundedMetadata constraintAnnotation) {
        this.maxEntries = constraintAnnotation.maxEntries();
        this.maxKeyLength = constraintAnnotation.maxKeyLength();
        this.maxValueLength = constraintAnnotation.maxValueLength();
    }

    @Override
    public boolean isValid(Map<String, String> value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true;
        }

        if (value.size() > maxEntries) {
            return fail(context, "metadata.too.many.entries");
        }

        for (Map.Entry<String, String> entry : value.entrySet()) {
            if (entry.getKey() != null && entry.getKey().length() > maxKeyLength) {
                return fail(context, "metadata.key.too.long");
            }
            if (entry.getValue() != null && entry.getValue().length() > maxValueLength) {
                return fail(context, "metadata.value.too.long");
            }
        }
        return true;
    }

    private boolean fail(ConstraintValidatorContext context, String messageKey) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(messageKey).addConstraintViolation();
        return false;
    }
}
