// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.policy;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** Request body for the preflight naming check. */
@Getter
@Setter
@Schema(name = "NamingCheckForm", description = "Candidate external ids to check against the naming policy.")
public class NamingCheckForm {

    @NotEmpty
    @Size(max = 1000)
    @Schema(description = "External ids to check. Nothing is written.",
            example = "[\"COM-99-PT-1034\", \"pump-a-01\"]")
    private List<String> externalIds = new ArrayList<>();

    /**
     * Names for the ids above, aligned by position. Optional, and worth supplying.
     *
     * <p>A suggestion is derived from the name where there is one, because that is what a human
     * chose: an entity named "Valve pressure sensors" gets offered {@code valve_pressure_sensors},
     * where deriving from a broken id would only manage {@code vps}. Without names the check still
     * works, it just has less to go on.
     *
     * <p>Positional pairing is a footgun if it drifts, so a length mismatch is rejected outright
     * rather than silently pairing the wrong name with the wrong id — see {@link #nameFor(int)} and
     * the size check in the controller.
     */
    @Size(max = 1000)
    @Schema(description = "Optional names for the external ids above, aligned by position. Used to "
            + "derive a more meaningful suggestion. Either omit entirely or supply exactly as many "
            + "as there are external ids.",
            example = "[\"Valve 21 PT 1034\", \"Pump A 01\"]")
    private List<String> names = new ArrayList<>();

    @JsonAlias({"data_set_id", "dataSetId"})
    @Schema(description = "Check against the policy governing this data set. Omit for the tenant policy.",
            example = "12")
    private Long dataSetId;

    /** Whether names were supplied at all. Absent is fine; partially present is not. */
    public boolean hasNames() {
        return names != null && !names.isEmpty();
    }

    /** The name paired with position {@code index}, or null when none was supplied. */
    public String nameFor(int index) {
        if (!hasNames() || index >= names.size()) {
            return null;
        }
        return names.get(index);
    }
}
