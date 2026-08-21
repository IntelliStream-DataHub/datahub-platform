// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.validation.resources;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;

@Slf4j
public class AtLeastOneNotNullValidator implements ConstraintValidator<AtLeastOneNotNull, Object> {
    private String[] fieldNames;

    @Override
    public void initialize(AtLeastOneNotNull constraintAnnotation) {
        this.fieldNames = constraintAnnotation.fieldNames();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // null values are considered valid
        }

        for (String fieldName : fieldNames) {
            try {
                Field field = value.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                if (field.get(value) != null) {
                    return true; // At least one field is not null
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                // Handle exception or log it
                log.error(e.getMessage(), e);
            }
        }
        return false; // None of the specified fields are non-null
    }
}

