// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.function;

import ai.intellistream.datahub.helpers.updates.UpdateListField;
import ai.intellistream.datahub.helpers.updates.UpdateMapField;
import ai.intellistream.datahub.helpers.updates.UpdateNumberField;
import ai.intellistream.datahub.helpers.updates.UpdateStringField;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * The fields of a {@link Function} an update may change — exactly those. No {@code geoLocation}:
 * only assets have one, and the generic form used to accept it here and silently drop it.
 *
 * <p>Validated by the shared node-update pipeline, which applies the same rules to every node type.
 */
@Getter
@Setter
@Schema(name = "FunctionFields", description = "The function fields to change. Omitted fields are left as they are.")
public class FunctionFields {

    private UpdateStringField externalId = new UpdateStringField();
    private UpdateStringField name = new UpdateStringField();
    private UpdateStringField description = new UpdateStringField();
    private UpdateNumberField dataSetId = new UpdateNumberField();
    private UpdateMapField metadata = new UpdateMapField();
    private UpdateStringField source = new UpdateStringField();
    private UpdateListField labels = new UpdateListField();
}
