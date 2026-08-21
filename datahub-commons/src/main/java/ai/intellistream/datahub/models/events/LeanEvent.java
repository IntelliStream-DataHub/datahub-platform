// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.events;

import ai.intellistream.datahub.json.ToStringSerializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.annotation.JsonSerialize;

import java.util.List;
import java.util.Map;

/**
 * Compact, LLM-friendly view of an event for drill-down: just the fields a caller needs to
 * understand what happened. The heavy/low-signal fields on the full event model — UUID id,
 * created/updated system timestamps, empty collections, empty source — are intentionally
 * dropped, and {@link JsonInclude.Include#NON_EMPTY} omits any null/empty field so the
 * serialized payload stays small.
 *
 * <p>{@code dataSetId} is string-serialised like its {@code LeanResource}/{@code LeanTimeseries}
 * siblings; it was the one lean projection still emitting a raw numeric node id.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record LeanEvent(
        String externalId,
        String type,
        String subType,
        String eventTime,
        String description,
        @JsonSerialize(using = ToStringSerializer.class) Long dataSetId,
        Map<String, String> metadata,
        List<String> relatedResourceExternalIds
) {}
