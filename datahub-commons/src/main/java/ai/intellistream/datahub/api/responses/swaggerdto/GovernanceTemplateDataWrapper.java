// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.models.GovernanceTemplateDTO;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "Governance Template Collection", description = "Data response with a collection of governance templates.")
public class GovernanceTemplateDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<GovernanceTemplateDTO> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<GovernanceTemplateDTO> getItems() {
        return items;
    }
}
