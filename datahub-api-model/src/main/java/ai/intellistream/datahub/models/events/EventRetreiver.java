// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.events;

import ai.intellistream.datahub.models.DataSort;
import ai.intellistream.datahub.models.datafilters.FilterDefaults;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Schema(name = "EventRetreiver", description = "Configure how you want to fetch events.")
@Getter @Setter
public class EventRetreiver {

    // @Valid so EventFilter's own constraints run; see ResourceRetreiver.filter.
    @Valid
    private EventFilter filter = new EventFilter();

    /** @see ai.intellistream.datahub.models.DataSetRetreiver#limit */
    @JsonSetter(nulls = Nulls.SKIP)
    @Max(FilterDefaults.MAX_LIMIT)
    private int limit = FilterDefaults.DEFAULT_LIMIT;

    /** @see #limit */
    public void setLimit(int limit) {
        this.limit = (limit <= 0) ? FilterDefaults.DEFAULT_LIMIT : limit;
    }

    /**
     * Where the previous page stopped, or null to start from the beginning:
     * {@code <eventTime as epoch millis>_<event id>}, taken from the last event of that page.
     *
     * <p><b>Why a pair and not just a timestamp.</b> Event times are not unique — a bulk ingest
     * lands thousands of events in the same millisecond — so a cursor on the timestamp alone would
     * either skip that whole group or repeat it forever. The id breaks the tie, and because it is
     * unique the pair is a total order.
     *
     * <p><b>Why a cursor and not an offset.</b> Events are stored partitioned by month on event
     * time, so resuming from a timestamp lets whole partitions be skipped before a row is read:
     * page 400 costs what page 1 costs. An offset has to read and order everything ahead of it, so
     * the same query degrades as the tenant accumulates events. The trade is that there is no random
     * access — a caller walks forward from where it was and cannot jump to page 7.
     *
     * <p>Setting this fixes the result order to {@code (eventTime, id)} ascending, overriding
     * {@link #sort}: a cursor is only meaningful against the order it was produced in, and reading
     * it back in another order would skip rows silently rather than fail. A malformed value is
     * ignored, which restarts from the beginning rather than returning a wrong page.
     */
    @Schema(description = "Where the previous page stopped: `<eventTime epoch millis>_<event id>`, "
            + "from the last event of that page. Omit to start from the beginning. Fixes the order "
            + "to eventTime then id ascending.",
            example = "1754476522104_0195f3a2-4c1b-7f9e-9c3a-1b2d4e6f8a90")
    @Size(max = 4096)
    private String cursor;

    private DataSort sort = new DataSort();

    /**
     * A boolean expression, in the filter language documented with the events API.
     *
     * <p>Replaces the nested {@code and}/{@code or}/{@code not} JSON this field used to carry. A
     * caller writes what they would write in a WHERE clause — {@code type NOT LIKE 'pump' AND
     * (subType = 'water' OR subType = 'gas')} — and the api parses it into a tree and renders it
     * as a parameterised query. Nothing here is ever concatenated into SQL.
     *
     * <p>The dialect is PostgreSQL-flavoured: PostgreSQL function names, {@code ::} casts,
     * {@code ILIKE}, and {@code <>} alongside {@code !=}. Metadata values are text, so comparing
     * one as anything else needs a converter ({@code to_int}, {@code to_number}, {@code to_bool},
     * {@code to_date}, {@code to_timestamp}).
     */
    @Size(max = 4096)
    @Schema(description = "A boolean filter expression, PostgreSQL-flavoured. Combined with "
            + "`filter` by AND.",
            example = "type NOT LIKE 'pump' AND (subType = 'water' OR subType = 'gas')")
    private String advancedFilter;

}
