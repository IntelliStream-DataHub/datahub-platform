// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.config;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowConfigTest {

    private static final long GB = DataSize.ofGigabytes(1).toBytes();

    @Test
    void eachRunGetsAnEqualShareOfHalfTheHeap() {
        assertThat(FlowConfig.runMemoryCap("", 16 * GB, 8)).isEqualTo(DataSize.ofGigabytes(1));
        assertThat(FlowConfig.runMemoryCap("", 4 * GB, 8)).isEqualTo(DataSize.ofMegabytes(256));
        assertThat(FlowConfig.runMemoryCap("", 4 * GB, 1)).isEqualTo(DataSize.ofGigabytes(2));
    }

    @Test
    void aSmallerBudgetIsSplitInstead() {
        assertThat(FlowConfig.runMemoryCap("1GB", 16 * GB, 8)).isEqualTo(DataSize.ofMegabytes(128));
    }

    @Test
    void aBudgetOverHalfTheHeapStopsTheBoot() {
        assertThatThrownBy(() -> FlowConfig.runMemoryCap("3GB", 4 * GB, 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than half the maximum heap");
    }

    @Test
    void aShareTooSmallForARunStopsTheBoot() {
        assertThatThrownBy(() -> FlowConfig.runMemoryCap("", 512 * 1024 * 1024, 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gives each run 32 MB")
                .hasMessageContaining("lower datahub.flow.runs.max-running");
        assertThatThrownBy(() -> FlowConfig.runMemoryCap("256MB", 4 * GB, 8))
                .hasMessageContaining("under the 64 MB a run needs");
    }
}
