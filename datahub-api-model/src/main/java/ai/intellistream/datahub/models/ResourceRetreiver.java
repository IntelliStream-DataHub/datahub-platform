// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.models.datafilters.FilterDefaults;
import ai.intellistream.datahub.models.datafilters.ResourceFilter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Request body of {@code POST /resources/filter}. */
@Data
@Schema(name = "Resource Query", description = "Resource Query Object")
public class ResourceRetreiver {

    // @Valid so the constraints declared on ResourceFilter actually run. Without it the nested
    // object was never traversed, and every @Size on the filter was decorative.
    @Valid
    private ResourceFilter filter = new ResourceFilter();

    /** @see DataSetRetreiver#limit */
    @JsonSetter(nulls = Nulls.SKIP)
    @Max(FilterDefaults.MAX_LIMIT)
    private int limit = FilterDefaults.DEFAULT_LIMIT;

    /** @see #limit */
    public void setLimit(int limit) {
        this.limit = (limit <= 0) ? FilterDefaults.DEFAULT_LIMIT : limit;
    }


    /**
     * The order to return rows in: one sortable property plus the {@code id} tie-breaker the query
     * appends. Absent means newest created first.
     *
     * @see ai.intellistream.datahub.models.paging.PageCursor
     */
    @Valid
    private DataSort sort;

    /**
     * Where a previous page stopped. Send back the {@code nextCursor} from the last response, with
     * the same {@code sort} it came from — a cursor is a position in one particular order, so
     * continuing it under a different one is rejected rather than answered with a wrong page.
     */
    // 4096 is far more than any cursor this API mints and far less than a caller can weaponise:
    // the value is base64-decoded before anything looks at it, so an unbounded field is an
    // unbounded decode and an unbounded error message.
    @Size(max = 4096)
    @Schema(description = "Opaque cursor from a previous response's `nextCursor`. "
            + "Must be sent with the same `sort` that produced it.")
    private String cursor;
}
