// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import ai.intellistream.datahub.helpers.text.TextValidator;
import ai.intellistream.datahub.validation.FieldValidationError;

import java.util.List;

/**
 * The external-id charset floor, applied identically wherever an external id is accepted.
 *
 * <p>{@code ResourceFields}, {@code DataSetFields}, {@code EventFields} and {@code TimeseriesFields}
 * each carried their own copy of this block, differing only in the message-key prefix. They had
 * already drifted — the copies disagreed about whether the length check short-circuits the format
 * check — and a floor that four classes implement separately is a floor with four heights. This is
 * the one implementation; the callers supply their object name.
 *
 * <p>Scope note: this is the floor, not the convention. It is all events are ever subject to. The
 * naming policy (presets, the near-duplicate guard, findings) sits above it and governs resources
 * and data sets only.
 */
public final class ExternalIdRules {

    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 256;

    private ExternalIdRules() {
    }

    /**
     * Validate one external id and append any problems to {@code errors}.
     *
     * <p>The value is <strong>not</strong> modified. Storage is verbatim: what the caller sends is
     * what is stored and what is read back.
     *
     * @param objectName  the entity name used in the error, e.g. {@code "Resource"}
     * @param keyPrefix   the i18n message-key prefix, e.g. {@code "resource"}. Passed rather than
     *                    derived from {@code objectName}, because they do not always agree —
     *                    {@code "Time Series"} keys off {@code "timeseries"}
     * @param externalId  the candidate external id
     * @param errors      collector the caller already owns
     */
    public static void validate(String objectName, String keyPrefix, String externalId,
                                List<FieldValidationError> errors) {
        if (externalId == null) {
            return;
        }

        if (externalId.length() < MIN_LENGTH) {
            errors.add(new FieldValidationError(
                    objectName,
                    new String[]{keyPrefix + ".external.id.min.length.error"},
                    new Object[]{externalId.length()},
                    "ExternalId min length is " + MIN_LENGTH + " characters."));
        } else if (externalId.length() > MAX_LENGTH) {
            errors.add(new FieldValidationError(
                    objectName,
                    new String[]{keyPrefix + ".external.id.max.length.error"},
                    new Object[]{externalId.length()},
                    "ExternalId max length is " + MAX_LENGTH + " characters."));
        }

        if (!TextValidator.validateExternalIdCharset(externalId)) {
            errors.add(new FieldValidationError(
                    objectName,
                    new String[]{keyPrefix + ".external.id.format.error"},
                    new Object[]{externalId},
                    "ExternalId can contain letters, digits and the characters . _ : + = - only. "
                            + "Spaces, slashes and control characters are not allowed. "
                            + "It is stored exactly as sent."));
        }
    }
}
