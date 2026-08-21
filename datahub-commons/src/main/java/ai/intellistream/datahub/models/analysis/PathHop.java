// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.analysis;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One hop of the graph relationship path from the focus to a candidate timeseries. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PathHop {

    private Long fromId;

    private Long toId;

    private String relationshipType;
}
