// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.helpers.updates.UpdateGeoLocationField;
import ai.intellistream.datahub.helpers.updates.UpdateListField;
import ai.intellistream.datahub.helpers.updates.UpdateMapField;
import ai.intellistream.datahub.helpers.updates.UpdateNumberField;
import ai.intellistream.datahub.helpers.updates.UpdateStringField;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * The fields of an {@link Asset} an update may change — exactly those, so {@code /assets/update}
 * documents and accepts the asset's own contract rather than the generic resource one.
 *
 * <p>Validated by the shared node-update pipeline, which applies the same rules to every node type.
 */
@Getter
@Setter
@Schema(name = "AssetFields", description = "The asset fields to change. Omitted fields are left as they are.")
public class AssetFields {

    private UpdateStringField externalId = new UpdateStringField();
    private UpdateStringField name = new UpdateStringField();
    private UpdateStringField description = new UpdateStringField();
    private UpdateNumberField dataSetId = new UpdateNumberField();
    private UpdateMapField metadata = new UpdateMapField();
    private UpdateStringField source = new UpdateStringField();
    private UpdateListField labels = new UpdateListField();
    private UpdateGeoLocationField geoLocation = new UpdateGeoLocationField();
}
