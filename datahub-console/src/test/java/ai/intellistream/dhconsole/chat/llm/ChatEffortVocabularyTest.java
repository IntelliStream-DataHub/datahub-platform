// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import ai.intellistream.datahub.agent.AgentEffort;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two enums name the same five levels, and they live in different modules for a reason:
 * {@link AgentEffort} is wire contract in {@code datahub-api-model}, so a client reading the API
 * schema learns the levels and a misspelled one is rejected on write; {@link ChatEffort} is the
 * behavioural enum here, which knows what each level costs and how it narrows onto the three
 * values the OpenAI-compatible wire defines. Neither belongs in the other's module.
 *
 * <p>What that buys has to be paid for: the two can drift, and the failure would be silent and
 * nasty — datahub-api accepting a level the runner cannot honour, so an agent stores a
 * {@code default_effort} that quietly does nothing. This is the payment.
 */
class ChatEffortVocabularyTest {

    @Test
    void bothEnumsNameExactlyTheSameLevels() {
        List<String> wire = Arrays.stream(AgentEffort.values()).map(AgentEffort::wireName).toList();
        List<String> behaviour = Arrays.stream(ChatEffort.values()).map(ChatEffort::wireName).toList();

        assertThat(behaviour).isEqualTo(wire);
    }

    @Test
    void everyLevelTheApiAcceptsIsOneTheRunnerCanHonour() {
        for (AgentEffort accepted : AgentEffort.values()) {
            // The fallback would mask a mismatch, so assert it was not taken.
            assertThat(ChatEffort.parse(accepted.wireName(), null))
                    .as(accepted.wireName())
                    .isNotNull();
        }
    }
}
