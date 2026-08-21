// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.datafilters;

import ai.intellistream.datahub.json.SingleOrList;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * Filter criteria for {@code POST /resources/filter} and the {@code filter} of
 * {@code POST /resources/search}.
 *
 * <p><b>This is the generic node query.</b> Unlike {@code /datasets/filter},
 * {@code /timeseries/filter} and {@code /events/filter}, which each answer for one type, this one
 * spans every node type — assets, timeseries, functions, resources, data sets and policies all live
 * in the same table and share the criteria on {@link NodeFilter}, so one endpoint can search across
 * them. Narrow it with {@link #nodeType} when you want only some.
 *
 * <p>Every node carries its type as a label, so a caller can tell what came back without asking for
 * a type in the first place.
 *
 * <p>{@code id}, {@code externalId} and {@code name} once existed here as scalars <em>alongside</em>
 * the plural list forms — six fields for three concepts, ANDed rather than merged, so sending both
 * narrowed the query in a way no caller intended. The scalars were removed, and the surviving lists
 * have since taken those names back. They are still lists; they simply accept a bare value as well,
 * so a request body that named one thing means what it always did.
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(name = "Resource Query Filter", description = "Resource Query Filter Object")
public class ResourceFilter extends DataSetScopedFilter {

    /**
     * Restrict to these node types: {@code asset}, {@code timeseries}, {@code function},
     * {@code resource}, {@code dataset} or {@code policy}. Case-insensitive, and entries OR
     * together.
     *
     * <p>Omitted means every type, which is what makes this the generic query. A name matching no
     * known type contributes nothing, so a list of only unknown names matches nothing rather than
     * quietly widening back to everything.
     */
    @SingleOrList
    @Size(max = 20)
    @Schema(description = "Restrict to these node types. Omit for every type.",
            example = "[\"resource\", \"timeseries\"]")
    private List<String> nodeType;

    /**
     * Root nodes only ({@code true}) or non-root only ({@code false}).
     *
     * <p>Stays here rather than moving to {@link NodeFilter} even though the column is shared: only
     * resources and assets form the graph roots the console's tree view hangs off, and data sets
     * are never roots at all, so offering it on the typed data set query would be offering a filter
     * that always answers the same way.
     */
    @Schema(description = "Restrict to root nodes, or to non-root ones.", example = "true")
    private Boolean isRoot;
}
