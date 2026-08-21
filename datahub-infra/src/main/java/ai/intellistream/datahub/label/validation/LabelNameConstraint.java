// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.label.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = LabelValidator.class)
@Target( { ElementType.METHOD, ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
public @interface LabelNameConstraint {

    // All assets will have ASSET label, there is no need to create an
    // additional label name named asset

    String message() default "invalid.label.name.asset";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
