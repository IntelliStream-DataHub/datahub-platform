// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.json.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * One entry of {@code POST /assets/update}: which asset, by {@code id} or {@code externalId}, and
 * the {@link AssetFields} to change on it.
 */
@Getter
@Schema(name = "UpdateAssetForm", description = "An asset to update and the fields to change on it")
public class UpdateAssetForm {

    @Schema(description = "The id of the asset. Takes precedence over externalId.", example = "123466453")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "The external id of the asset.", example = "pump_a")
    // The external-id charset floor, as on UpdateResourceForm.
    @Pattern(regexp = "[A-Za-z0-9._:+=-]+")
    private String externalId;

    private AssetFields update = new AssetFields();

    public UpdateAssetForm setId(Long id) {
        this.id = id;
        return this;
    }

    public UpdateAssetForm setExternalId(String externalId) {
        this.externalId = externalId;
        return this;
    }

    public UpdateAssetForm setUpdate(AssetFields update) {
        this.update = update;
        return this;
    }
}
