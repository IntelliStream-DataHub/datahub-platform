// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.models.forms.DataSetForm;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;

import java.util.ArrayList;
import java.util.Collection;

public class DataSetFormDataWrapper {

    @JacksonXmlElementWrapper(useWrapping = false)
    private Collection<DataSetForm> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<DataSetForm> getItems() {
        return items;
    }
}
