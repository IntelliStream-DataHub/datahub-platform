// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.resource;

import ai.intellistream.datahub.label.LabelForm;
import ai.intellistream.datahub.models.GeoLocation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Schema(name="CreateResourceForm", description="Describes how to create resources.")
@Getter
@Setter
public class ResourceForm extends NodeForm {

    /**
     * The name of the data source containing the primary information about the
     * asset.
     */
    @Pattern(regexp = "^$|.{2,128}", message = "pattern.resource.source.error")
    private String source;

    /**
     * A list of the labels associated with this asset.
     */
    @Size(min = 1, message = "resource.needs.at.least.one.label")
    private List<String> labels = new ArrayList<>();

    /**
     * Geographic data.
     */
    @Valid
    private GeoLocation geoLocation;


    public ResourceForm() {
    }

}
