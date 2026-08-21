// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceIdentityTest {

    @Test
    void parsesSingleBoundNumaNode() {
        assertThat(InstanceIdentity.parseNumaNode("1")).isEqualTo("1");
        assertThat(InstanceIdentity.parseNumaNode(" 0 ")).isEqualTo("0");
    }

    @Test
    void treatsUnpinnedRangeOrListAsNoNode() {
        // Unpinned processes see every node — a range/list, not a single id.
        assertThat(InstanceIdentity.parseNumaNode("0-1")).isNull();
        assertThat(InstanceIdentity.parseNumaNode("0,2")).isNull();
        assertThat(InstanceIdentity.parseNumaNode("")).isNull();
        assertThat(InstanceIdentity.parseNumaNode(null)).isNull();
    }

    @Test
    void composesHostAndNumaWhenPinned() {
        assertThat(InstanceIdentity.compute("api-host-01", "1")).isEqualTo("api-host-01-numa1");
    }

    @Test
    void fallsBackToHostOnlyWhenNumaUnknown() {
        assertThat(InstanceIdentity.compute("api-host-01", "0-1")).isEqualTo("api-host-01");
        assertThat(InstanceIdentity.compute("api-host-01", null)).isEqualTo("api-host-01");
    }

    @Test
    void sanitizesCharactersUnsafeInPulsarNames() {
        // FQDN dots and other punctuation become dashes so the id is safe in a producer name.
        assertThat(InstanceIdentity.compute("api.dc.example.com", "2")).isEqualTo("api-dc-example-com-numa2");
        assertThat(InstanceIdentity.sanitize("a b/c:d")).isEqualTo("a-b-c-d");
    }

    @Test
    void sanitizeNeverReturnsBlank() {
        assertThat(InstanceIdentity.sanitize("")).isEqualTo("unknown-host");
        assertThat(InstanceIdentity.sanitize(null)).isEqualTo("unknown-host");
    }

    @Test
    void liveIdIsNonBlankAndSafe() {
        assertThat(InstanceIdentity.get()).isNotBlank().matches("[A-Za-z0-9-]+");
    }
}
