// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.validation;

import ai.intellistream.datahub.helpers.updates.UpdateMapField;
import ai.intellistream.datahub.validation.FieldValidationError;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The {@link FieldLimits} ceilings for the hand-written update validators.
 *
 * <p>Create validates through bean-validation annotations; update validates by hand, and the two
 * paths had drifted — description and metadata were bounded on neither, which left update as a way
 * to put into an entity exactly what create had started refusing.
 */
public final class SizeRules {

    private SizeRules() {
    }

    /** Record an error if a {@code set} string exceeds {@code max}. */
    public static void checkLength(String objectName, String messageKey, String fieldLabel,
                                   String value, int max, List<FieldValidationError> errors) {
        if (value == null || value.length() <= max) {
            return;
        }
        errors.add(new FieldValidationError(
                objectName,
                new String[]{messageKey},
                new Object[]{value.length()},
                fieldLabel + " max length is " + max + " characters."));
    }

    /** Record an error if a {@code set}/{@code add} collection holds more than {@code max} entries. */
    public static void checkCount(String objectName, String messageKey, String fieldLabel,
                                  Collection<?> value, int max, List<FieldValidationError> errors) {
        if (value == null || value.size() <= max) {
            return;
        }
        errors.add(new FieldValidationError(
                objectName,
                new String[]{messageKey},
                new Object[]{value.size()},
                fieldLabel + " may hold at most " + max + " entries."));
    }

    /**
     * Bound a metadata update: entry count, key length and value length, on both {@code set} (which
     * replaces the map) and {@code add} (which grows it). {@code add} is checked against the same
     * entry cap because the resulting total is not knowable here — this bounds what one request can
     * push, which is what the abuse case turns on.
     */
    public static void checkMetadata(String objectName, String keyPrefix, UpdateMapField metadata,
                                     List<FieldValidationError> errors) {
        checkMetadataMap(objectName, keyPrefix, metadata.getSet(), errors);
        checkMetadataMap(objectName, keyPrefix, metadata.getAdd(), errors);
    }

    private static void checkMetadataMap(String objectName, String keyPrefix, Map<String, String> map,
                                         List<FieldValidationError> errors) {
        if (map == null || map.isEmpty()) {
            return;
        }
        if (map.size() > FieldLimits.METADATA_MAX_ENTRIES) {
            errors.add(new FieldValidationError(
                    objectName,
                    new String[]{keyPrefix + ".metadata.too.many.entries"},
                    new Object[]{map.size()},
                    "Metadata may hold at most " + FieldLimits.METADATA_MAX_ENTRIES + " entries."));
        }
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getKey().length() > FieldLimits.METADATA_KEY_MAX) {
                errors.add(new FieldValidationError(
                        objectName,
                        new String[]{keyPrefix + ".metadata.key.too.long"},
                        new Object[]{entry.getKey().length()},
                        "Metadata key max length is " + FieldLimits.METADATA_KEY_MAX + " characters."));
            }
            if (entry.getValue() != null && entry.getValue().length() > FieldLimits.METADATA_VALUE_MAX) {
                errors.add(new FieldValidationError(
                        objectName,
                        new String[]{keyPrefix + ".metadata.value.too.long"},
                        new Object[]{entry.getValue().length()},
                        "Metadata value max length is " + FieldLimits.METADATA_VALUE_MAX + " characters."));
            }
        }
    }
}
