// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.validation.resources;

import ai.intellistream.datahub.models.RelForm;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class RelationshipTypeNotNullValidator implements ConstraintValidator<RelationshipTypeNotNull, RelForm> {
    @Override
    public boolean isValid(RelForm form, ConstraintValidatorContext context) {
        boolean hasName = form.getRelationshipType() != null && !form.getRelationshipType().isBlank();
        return hasName || form.getRelationshipTypeId() != null;
    }
}
