// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.subscription;

import ai.intellistream.datahub.models.DataSort;
import ai.intellistream.datahub.models.datafilters.FilterDefaults;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Request body of {@code POST /subscriptions/filter}. Mirrors {@code TimeseriesRetreiver} and the
 * rest of the family: one {@code filter} holding every row criterion, and the page cut around it —
 * {@code limit}, {@code sort}, {@code cursor} — spelled and defaulted identically.
 *
 * <p>It used to be its own shape: {@code POST /subscriptions/list} taking a {@code limit} that
 * defaulted to 100 where every other endpoint defaulted to 1000, a {@code sort} that reached the
 * query unvalidated, and no cursor at all. {@code RetrieverEnvelopeTest} is what keeps the four
 * node retrievers from drifting like that again; this class is now held to the same contract.
 */
@Schema(name = "Subscription Query", description = "Subscription Query Object")
@Getter
@Setter
public class SubscriptionRetriever {

    @Valid
    private SubscriptionFilter filter = new SubscriptionFilter();

    /** @see ai.intellistream.datahub.models.DataSetRetreiver#limit */
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
    @Size(max = 4096)
    @Schema(description = "Opaque cursor from a previous response's `nextCursor`. "
            + "Must be sent with the same `sort` that produced it.")
    private String cursor;

}
