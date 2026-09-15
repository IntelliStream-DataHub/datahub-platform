// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import ai.intellistream.datahub.function.Function;
import ai.intellistream.datahub.models.Asset;
import ai.intellistream.datahub.models.DataSetModel;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.Policy;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.timeseries.Timeseries;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import ai.intellistream.datahub.models.policy.PolicyWarning;
import java.util.ArrayList;
import java.util.Collection;

@Schema(name = "Node Collection",
        description = "Data response with a node collection. Items are typed by their type-label: "
                + "an element whose labels contain ASSET is an Asset, TIMESERIES a Timeseries, "
                + "DATASET a data set, POLICY a policy, FUNCTION a function; an element with no "
                + "type-label is a plain Resource.")
public class ResourceDataWrapper{

    @JacksonXmlElementWrapper(useWrapping = false)
    @ArraySchema(schema = @Schema(anyOf = {
            Asset.class, Resource.class, Timeseries.class,
            DataSetModel.class, Policy.class, Function.class}))
    private Collection<NodeModel> items = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Collection<NodeModel> getItems() {
        return items;
    }

    /**
     * Mirrors {@code DataWrapper.nextCursor}. These documentation envelopes exist because
     * {@code DataWrapper} carries a fixed {@code @Schema(name)}, so every generic instantiation
     * would otherwise document as one untyped schema — and each copy was written when {@code items}
     * was the whole envelope. The real one grew a cursor; the copies did not, so the published spec
     * described a response with no way to page and a generated client had no field to read, while
     * the endpoint descriptions told callers to loop on exactly this value.
     *
     * <p>Read-only: the live envelope does accept it on a request body, but that is a defect to fix
     * there, not a shape to publish here.
     */
    @Schema(accessMode = Schema.AccessMode.READ_ONLY,
            description = "Opaque cursor for the next page. Send it back as `cursor`. "
                    + "Absent when there are no further pages.")
    private String nextCursor;

    public String getNextCursor() {
        return nextCursor;
    }

    /**
     * Mirrors {@code DataWrapper.warnings}. Attached on the way out by
     * {@code PolicyWarningResponseAdvice} for the write paths that evaluate policies
     * ({@code ResourceService}, {@code TimeseriesService}, {@code DataSetService},
     * {@code GraphTransferService}), and absent when there is nothing to report.
     */
    @Schema(accessMode = Schema.AccessMode.READ_ONLY,
            description = "Policy violations that were allowed through and recorded for review. "
                    + "Absent when there are none.")
    private Collection<PolicyWarning> warnings;

    public Collection<PolicyWarning> getWarnings() {
        return warnings;
    }
}
