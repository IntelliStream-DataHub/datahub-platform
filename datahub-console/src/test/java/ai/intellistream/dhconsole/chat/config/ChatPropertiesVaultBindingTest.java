// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.dhconsole.chat.llm.ChatEffort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vault hands every value over as a {@code String}, so the typed settings only work if relaxed
 * binding converts them. Three of them are not strings — an enum, a {@code Duration} and an
 * {@code Integer} — and a value that fails to convert is a startup failure in a deployment nobody
 * runs locally, which is the worst place to discover it.
 */
class ChatPropertiesVaultBindingTest {

    private ChatProperties bind(Map<String, Object> vaultValues) {
        return new Binder(new MapConfigurationPropertySource(vaultValues))
                .bind("datahub.chat", Bindable.of(ChatProperties.class))
                .get();
    }

    @Test
    void theStringsVaultStoresConvertToTheirTypes() {
        Map<String, Object> vault = new HashMap<>();
        vault.put("datahub.chat.effort", "xhigh");
        vault.put("datahub.chat.max-output-tokens", "16000");
        vault.put("datahub.chat.turn-timeout", "10m");

        ChatProperties properties = bind(vault);

        assertThat(properties.getEffort()).isEqualTo(ChatEffort.XHIGH);
        assertThat(properties.getMaxOutputTokens()).isEqualTo(16_000);
        assertThat(properties.getTurnTimeout()).isEqualTo(Duration.ofMinutes(10));
    }

    /**
     * The loader only puts a key in when the field is present, so a secret that says nothing about
     * chat must leave every default intact — including the unset roof, which is what lets the effort
     * level choose the budget.
     */
    @Test
    void aSecretThatMentionsNoneOfThemLeavesTheDefaults() {
        ChatProperties properties = bind(Map.of("datahub.chat.enabled", "true"));

        assertThat(properties.getEffort()).isEqualTo(ChatEffort.DEFAULT);
        assertThat(properties.getMaxOutputTokens()).isNull();
        assertThat(properties.getTurnTimeout()).isEqualTo(Duration.ofMinutes(4));
    }

    /** Vault spells the level however the operator typed it; the enum is not case-sensitive here. */
    @Test
    void theEffortLevelIsNotCaseSensitive() {
        assertThat(bind(Map.of("datahub.chat.effort", "MAX")).getEffort()).isEqualTo(ChatEffort.MAX);
    }
}
