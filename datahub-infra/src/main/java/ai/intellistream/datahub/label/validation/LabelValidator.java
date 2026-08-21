// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.label.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;

public class LabelValidator implements ConstraintValidator<LabelNameConstraint, String> {

    private static final List<String> LABEL_NAMES = List.of(
            "ASSET",
            "TIMESERIES",
            "FILE",
            "EVENT",
            "SUBSCRIPTION"
    );

    @Override
    public boolean isValid(String labelName, ConstraintValidatorContext cxt) {
        String uppercasedLabelName = labelName.toUpperCase();
        return !LABEL_NAMES.contains(uppercasedLabelName);
    }

}
