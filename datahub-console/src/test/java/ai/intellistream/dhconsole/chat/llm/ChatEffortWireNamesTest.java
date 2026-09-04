// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import ai.intellistream.datahub.models.tenant.TenantLlmSettings;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The effort levels exist in two places and must not drift.
 *
 * <p>This enum is the real one; {@code TenantLlmSettings.EFFORT_LEVELS} is the list datahub-api
 * validates the settings form against and a client renders a picker from. They cannot share a
 * definition — the enum carries per-level token budgets and reasoning-effort mapping, console
 * concerns that have no business in the wire-contract library — so this pins them instead.
 *
 * <p>Without it, adding a level here would leave the api rejecting it as unknown, and the failure
 * would surface as a tenant unable to save a level their own picker offered.
 */
class ChatEffortWireNamesTest {

    @Test
    void theApiValidatesAgainstExactlyTheLevelsThisEnumDefines() {
        List<String> fromEnum = Arrays.stream(ChatEffort.values()).map(ChatEffort::wireName).toList();

        assertThat(TenantLlmSettings.EFFORT_LEVELS)
                .as("add the new level to TenantLlmSettings.EFFORT_LEVELS too, weakest first")
                .containsExactlyElementsOf(fromEnum);
    }

    @Test
    void everyLevelTheApiAcceptsParsesBackToTheEnum() {
        // The round trip that actually matters: a level saved through the settings form is read
        // back out of Vault by ChatEffort.parse, which falls back silently on anything it does not
        // know. A level that validated but did not parse would quietly do nothing.
        for (String level : TenantLlmSettings.EFFORT_LEVELS) {
            assertThat(ChatEffort.parse(level, null))
                    .as("api accepts '%s' but the console cannot parse it", level)
                    .isNotNull();
        }
    }
}
