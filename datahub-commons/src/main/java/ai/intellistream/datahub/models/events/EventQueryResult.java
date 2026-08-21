// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * Result of the {@code event_filter} tool, in one of two shapes depending on whether the
 * caller asked to aggregate:
 *
 * <ul>
 *   <li><b>Aggregate</b> (a {@code groupBy} was supplied): {@link #groupedBy}, {@link #buckets}
 *       (value + count, most frequent first), and {@link #total}. The events themselves are not
 *       returned — this answers "how many, broken down by X" cheaply.</li>
 *   <li><b>Drill-down</b> (no {@code groupBy}): {@link #events} as {@link LeanEvent}s, plus
 *       {@link #returned}, {@link #limit}, and {@link #truncated} so the caller knows whether
 *       more match than were returned.</li>
 * </ul>
 *
 * {@link JsonInclude.Include#NON_EMPTY} keeps whichever half is unused out of the payload.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Data
public class EventQueryResult {

    // --- aggregate shape ---
    private String groupedBy;
    private Long total;
    private List<Bucket> buckets;

    // --- drill-down shape ---
    private List<LeanEvent> events;
    private Integer returned;
    private Integer limit;
    private Boolean truncated;

    public record Bucket(String value, long count) {}
}
