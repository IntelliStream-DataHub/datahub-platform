// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.models.files.IndexNode;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "File Collection", description = "Data response with a collection of files and folders.")
public class FileDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<IndexNode> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<IndexNode> getItems() {
        return items;
    }
}
