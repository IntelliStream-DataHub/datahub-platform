// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deploy round-trip's state is the pending deployment itself, so the callback must be able to
 * tell a genuine one from one that was forged, altered or kept too long.
 */
class DeployStateTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final byte[] KEY = DeployState.key("changeme-flow");

    private static DeployState state() {
        return new DeployState("org-foo", 42, 3, true, "user-1", "nonce", NOW.plusSeconds(600));
    }

    @Test
    void aSignedStateComesBackAsItWent() {
        assertThat(DeployState.verify(state().sign(KEY, JSON), KEY, JSON, NOW)).isEqualTo(state());
    }

    @Test
    void anAlteredPayloadIsRefused() {
        String signed = state().sign(KEY, JSON);
        String forged = new DeployState("org-foo", 42, 3, true, "attacker", "nonce", NOW.plusSeconds(600))
                .sign(KEY, JSON);
        // The attacker's own payload with the genuine signature.
        String spliced = forged.substring(0, forged.indexOf('.')) + signed.substring(signed.indexOf('.'));

        assertThatThrownBy(() -> DeployState.verify(spliced, KEY, JSON, NOW))
                .isInstanceOf(DeployState.InvalidState.class);
    }

    @Test
    void aStateSignedWithAnotherKeyIsRefused() {
        String signed = state().sign(DeployState.key("another-secret"), JSON);

        assertThatThrownBy(() -> DeployState.verify(signed, KEY, JSON, NOW))
                .isInstanceOf(DeployState.InvalidState.class);
    }

    @Test
    void anExpiredStateIsRefused() {
        String signed = state().sign(KEY, JSON);

        assertThatThrownBy(() -> DeployState.verify(signed, KEY, JSON, NOW.plusSeconds(600)))
                .isInstanceOf(DeployState.InvalidState.class)
                .hasMessageContaining("too long");
    }

    @Test
    void garbageIsRefusedNotThrownAsSomethingElse() {
        String unsignedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{}".getBytes(StandardCharsets.UTF_8));
        for (String garbage : new String[]{null, "", "no-dot", ".sig", unsignedPayload + ".AAAA"}) {
            assertThatThrownBy(() -> DeployState.verify(garbage, KEY, JSON, NOW))
                    .isInstanceOf(DeployState.InvalidState.class);
        }
    }
}
