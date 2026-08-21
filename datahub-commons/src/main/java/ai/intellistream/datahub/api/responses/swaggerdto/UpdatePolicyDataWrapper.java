// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.models.forms.UpdatePolicyForm;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "Update Policy Collection", description = "Request with a collection of policy updates.")
public class UpdatePolicyDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<UpdatePolicyForm> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<UpdatePolicyForm> getItems() {
        return items;
    }
}
