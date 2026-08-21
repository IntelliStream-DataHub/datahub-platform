// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AllowedAggregatesValidator.class)
public @interface AllowedAggregates {

    String message() default "Invalid aggregate value. Allowed values are avg, sum, min, max";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
