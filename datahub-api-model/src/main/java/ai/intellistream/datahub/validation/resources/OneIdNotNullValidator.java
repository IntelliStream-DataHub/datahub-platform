// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.validation.resources;

import ai.intellistream.datahub.models.RelatedResourcesForm;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class OneIdNotNullValidator implements ConstraintValidator<OneIdNotNull, RelatedResourcesForm> {
    @Override
    public boolean isValid(RelatedResourcesForm form, ConstraintValidatorContext context) {
        return form.getId() != null || form.getExternalId() != null;
    }
}
