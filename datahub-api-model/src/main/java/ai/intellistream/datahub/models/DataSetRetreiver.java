// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models;

import ai.intellistream.datahub.models.datafilters.DataSetFilter;
import ai.intellistream.datahub.models.datafilters.FilterDefaults;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Request body of {@code POST /datasets/filter}. */
@Getter @Setter
@Schema(name = "Data Set Query", description = "Data Set Query Object")
public class DataSetRetreiver {

    // @Valid so the constraints declared on the filter actually run. Without it the nested object
    // is never traversed and every @Size on it is decorative.
    @Valid
    private DataSetFilter filter = new DataSetFilter();

    /**
     * Maximum rows to return.
     *
     * <p>Typed defensively, because every failure mode here is silent rather than loud. A primitive
     * so it cannot arrive null and NPE at {@code setMaxResults}. {@code Nulls.SKIP} so an explicit
     * {@code "limit": null} keeps the default instead of relying on Jackson's primitive-null
     * coercion, which would otherwise hand this field a 0. And the setter guard because SQL reads
     * {@code LIMIT 0} as "return nothing", which is indistinguishable from "nothing matched".
     *
     * <p>Was @Size(max = 10000), which Hibernate Validator cannot apply to an int — validating the
     * form threw UnexpectedTypeException rather than rejecting an oversized limit. @Max is the
     * constraint that works on a numeric field.
     */
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
