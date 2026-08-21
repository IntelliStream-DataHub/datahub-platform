// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.models.analysis;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One point of a magnitude-squared coherence spectrum: a timescale and how related the pair is there. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CoherencePoint {

    /** Cycles per second. */
    private double frequency;

    /** Period (1/frequency), in seconds — the timescale. */
    private double periodSeconds;

    /** Magnitude-squared coherence in [0, 1]. */
    private double coherence;
}
