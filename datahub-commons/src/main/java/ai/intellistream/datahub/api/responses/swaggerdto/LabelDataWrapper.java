// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.label.LabelForm;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "Label Collection", description = "Data Response with Label collection.")
public class LabelDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<LabelForm> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<LabelForm> getItems() {
        return items;
    }
}
