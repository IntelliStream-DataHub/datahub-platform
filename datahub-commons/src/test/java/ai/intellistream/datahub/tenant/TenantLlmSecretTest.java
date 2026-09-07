// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The credential must not appear in a string representation.
 *
 * <p>Lombok's {@code @Data} prints every field, so this class is one {@code log.debug("{}", tenant)}
 * away from writing a tenant's API key into the log — where it would then sit in whatever collects
 * logs, long after the key was rotated. Nothing logs it today; this is here so nothing starts.
 */
class TenantLlmSecretTest {

    @Test
    void toStringNeverCarriesTheApiKey() {
        TenantLlm llm = new TenantLlm();
        llm.setProvider(LlmProvider.ANTHROPIC);
        llm.setModel("claude-opus-5");
        llm.setApiKey("sk-ant-do-not-log-me");

        assertThat(llm.toString())
                .doesNotContain("sk-ant-do-not-log-me")
                .contains("claude-opus-5");
    }
}
