// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bounds a metadata map: how many entries it may hold, and how long each key and value may be.
 * Defaults come from {@link FieldLimits}.
 */
@Constraint(validatedBy = BoundedMetadataValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface BoundedMetadata {

    String message() default "metadata.too.large";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    int maxEntries() default FieldLimits.METADATA_MAX_ENTRIES;

    int maxKeyLength() default FieldLimits.METADATA_KEY_MAX;

    int maxValueLength() default FieldLimits.METADATA_VALUE_MAX;
}
