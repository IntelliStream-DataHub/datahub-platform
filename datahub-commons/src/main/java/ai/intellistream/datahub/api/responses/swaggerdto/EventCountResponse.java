// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The count endpoint answers with a bare {@code {"count": n}} object rather than the usual
 * {@code items[]} envelope, so it needs its own schema.
 */
@Schema(name = "Event Count", description = "The number of events matching the request.")
public class EventCountResponse {

    @Schema(description = "Number of events.", example = "1423")
    private long count;

    public long getCount() {
        return count;
    }
}
