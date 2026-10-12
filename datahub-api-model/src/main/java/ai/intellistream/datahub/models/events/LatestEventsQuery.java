// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.events;

import ai.intellistream.datahub.json.SingleOrList;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Body of {@code POST /events/latest}: the latest event of each lifecycle, kept when that latest
 * event's status is one of {@link #status}.
 *
 * <p>Its own envelope rather than a field on {@link EventRetreiver}, because the SDKs serialise
 * absent fields as null and the api rejects fields it does not know: a new field there would make
 * every newer SDK fail against an older server.
 */
@Schema(name = "LatestEventsQuery", description = "Find the latest event of each lifecycle.")
@Getter @Setter
public class LatestEventsQuery {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    /**
     * Which events are considered. Its {@code status} is refused: the status test belongs to the
     * latest event, which is what {@link #status} is for.
     */
    @Valid
    @Schema(description = "Which events are considered, as for `POST /events/filter`. Must not set "
            + "`status`; use the top-level `status`, which applies to each lifecycle's latest event.")
    private EventFilter filter = new EventFilter();

    @SingleOrList
    @Size(max = 1000)
    @Schema(description = "Keep a lifecycle only when its latest event has one of these statuses. "
            + "`*` and `%` are wildcards. Omit for any status.",
            example = "[\"open\"]")
    private List<String> status;

    @JsonSetter(nulls = Nulls.SKIP)
    @Max(MAX_LIMIT)
    @Schema(description = "At most this many lifecycles per page.", defaultValue = "100",
            maximum = "1000")
    private int limit = DEFAULT_LIMIT;

    /** Zero or less means the default, as on {@link EventRetreiver#setLimit}. */
    public void setLimit(int limit) {
        this.limit = (limit <= 0) ? DEFAULT_LIMIT : limit;
    }

    @Size(max = 4096)
    @Schema(description = "The `nextCursor` of the previous page, or omit to start from the newest.")
    private String cursor;
}
