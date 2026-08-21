// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.models.Policy;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;

// Was "Data Set Collection", which collided with DataSetDataWrapper's schema name: two classes
// claiming one name means one silently overwrites the other, and every /datasets endpoint ended up
// documenting a list of policies. Schema names must be unique across the whole spec.
@Schema(name = "Policy Collection", description = "Data response with a collection of policies.")
public class PolicyDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<Policy> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<Policy> getItems() {
        return items;
    }
}
