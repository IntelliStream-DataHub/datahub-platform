// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.models.tenant.TenantLlmSettings;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The field names the settings page reads. A rename or a naming strategy here does not break a
 * build — it makes the browser read {@code undefined}, which is falsy, so every boolean silently
 * becomes "no". The two booleans are the ones that matter: {@code configured} drives a warning
 * banner and {@code apiKeySet} decides whether the key field says a key is stored.
 */
class TenantLlmSettingsJsonTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void serialisesTheNamesTheBrowserLooksFor() {
        var settings = new TenantLlmSettings("openai-compatible", "qwen3.8:latest",
                "http://localhost:11434/v1", null, "high", "10m", 64_000, 20, null, false, true);

        String body = json.writeValueAsString(settings);

        assertThat(body).contains("\"configured\":true")
                .contains("\"apiKeySet\":false")
                .contains("\"baseUrl\":")
                .contains("\"maxOutputTokens\":")
                .contains("\"maxIterations\":")
                .contains("\"turnTimeout\":")
                .contains("\"reasoningEffort\":");
        // The credential has no field at all, so no serialisation setting can start emitting it.
        assertThat(body).doesNotContain("apiKey\"");
    }
}
