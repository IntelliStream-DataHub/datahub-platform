// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import ai.intellistream.datahub.validation.FieldValidationError;

import java.util.List;

/**
 * Rejects {@code setNull} on the fields an object cannot exist without.
 *
 * <p>Every update field carries a {@code setNull} flag, but only some of the fields it can be
 * pointed at are actually nullable. Where the column is {@code NOT NULL} — {@code node.name},
 * {@code node.external_id}, the ClickHouse {@code events.type}/{@code external_id}/{@code
 * event_time} — or where create declares the field required, {@code setNull: true} used to fall
 * through every branch and return 200 having changed nothing. The caller was told the write
 * succeeded and read back the old value.
 *
 * <p>A silent no-op is the worst of the three options. Honouring it is impossible (the store
 * rejects it, or accepts it and leaves a row no client can deserialize — an event whose type is
 * blank fails every subsequent read against a model that declares type required). So the update
 * contract mirrors the create contract: a field create insists on is a field update cannot clear,
 * and asking to clear it is a 400 that names the field.
 *
 * <p>This says nothing about {@code set} — renaming is still fine. It is specifically "make it
 * absent" that has no valid outcome.
 */
public final class RequiredFieldRules {

    private RequiredFieldRules() {
    }

    /**
     * Record an error if {@code setNull} was requested on a field that cannot be null.
     *
     * @param objectName the entity name used in the error, e.g. {@code "Resource"}
     * @param messageKey the full i18n message key, e.g. {@code "resource.name.null.error"}. Passed
     *                   whole rather than assembled from a prefix, because the field segment does
     *                   not always match the property name — {@code externalId} keys off
     *                   {@code external.id}
     * @param fieldLabel the field as the caller named it, used in the fallback message
     * @param setNull    the flag as sent, already defaulted to false by the update field
     * @param errors     collector the caller already owns
     */
    public static void rejectSetNull(String objectName, String messageKey, String fieldLabel,
                                     boolean setNull, List<FieldValidationError> errors) {
        if (!setNull) {
            return;
        }
        errors.add(new FieldValidationError(
                objectName,
                new String[]{messageKey},
                new Object[]{},
                fieldLabel + " cannot be null."));
    }
}
