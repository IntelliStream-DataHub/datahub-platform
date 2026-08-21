// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Envelope for endpoints that return a list of distinct values rather than objects — the
 * event type / sub-type / status / source lookups, for example.
 */
@Schema(name = "Value Collection", description = "Data response with a collection of distinct values.")
public class StringValuesDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    @Schema(example = "[\"alarm\", \"maintenance\", \"inspection\"]")
    private Collection<String> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<String> getItems() {
        return items;
    }
}
