// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.forms;

import ai.intellistream.datahub.helpers.updates.UpdateBooleanField;
import ai.intellistream.datahub.helpers.updates.UpdateMapField;
import ai.intellistream.datahub.helpers.updates.UpdateNumberField;
import ai.intellistream.datahub.helpers.updates.UpdateStringField;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * Policy fields mapping for updating the object, the policy counterpart to {@code ResourceFields}
 * and {@code DataSetFields}.
 *
 * <p>Policy update used to take the plain {@code Policy} DTO and overlay it onto the entity, so
 * "the caller did not mention this field" had to be inferred from {@code null}. That works for
 * objects and fails for primitives: {@code isDeactivated} was a primitive {@code boolean}, so an
 * omitted property and an explicit {@code false} both arrived as {@code false} and every unrelated
 * edit silently re-activated a deactivated policy. Naming the intent explicitly — {@code set} /
 * {@code setNull} — removes the guesswork rather than patching each field that suffers from it.
 *
 * <p>Generics are not used as Avro Schemas doesn't support schema with generics.
 */
@Getter
@Setter
@Schema(name = "Policy Update Form", description = "Policy Update Form Object")
public class PolicyFields {

    @Schema(description = "Replace the display name. Required on the policy, so `setNull` is not honoured.")
    private UpdateStringField name = new UpdateStringField();

    @Schema(description = "Replace the external id; it is normalised and its hash re-derived. "
            + "Identity key, so `setNull` is not honoured.")
    private UpdateStringField externalId = new UpdateStringField();

    /** Nullable, so this one honours {@code setNull}. */
    private UpdateStringField description = new UpdateStringField();

    /** Nullable, so this one honours {@code setNull}. */
    private UpdateStringField source = new UpdateStringField();

    /** Carries each policy kind's own config (naming preset/pattern, lifecycle rules, templateId). */
    private UpdateMapField metadata = new UpdateMapField();

    /**
     * Whether the policy is switched off. A column on the node rather than a metadata entry, and
     * the reason this form exists: absent now means "leave it alone" instead of "activate it".
     */
    private UpdateBooleanField deactivated = new UpdateBooleanField();

    @Schema(description = "Apply a governance template, merging its metadata into the policy's. "
            + "`setNull` is not honoured — drop the template with `metadata.remove: [\"templateId\"]`.")
    private UpdateNumberField templateId = new UpdateNumberField();

}
