// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.validation.resources;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OneIdNotNullValidator.class)
public @interface OneIdNotNull {
    String message() default "at.least.one.id.should.contain.value";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
