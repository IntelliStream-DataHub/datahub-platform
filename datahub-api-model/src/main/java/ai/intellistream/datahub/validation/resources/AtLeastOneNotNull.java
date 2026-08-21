// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.validation.resources;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AtLeastOneNotNullValidator.class)
public @interface AtLeastOneNotNull {
    String message() default "At least one of the field properties must not be null";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    String[] fieldNames();
}

