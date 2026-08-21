// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rejects a timeseries value type that isn't one of the known types, so a typo (e.g. "flot")
 * surfaces as a 400 at create time instead of silently falling back to the default downstream.
 */
@Constraint(validatedBy = AllowedValueTypeValidator.class)
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedValueType {

    String message() default "Unknown value type. Allowed: BIGINT, FLOAT, FLOAT32, NUMERIC, DECIMAL32, TEXT, MIXED.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
