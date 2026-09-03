// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;

/**
 * A tenant's model configuration, from its {@code tenant-llm/<org-id>} secret:
 *
 * <pre>
 * provider=anthropic api-key=sk-ant-... model=claude-opus-5
 * provider=openai-compatible base-url=http://vllm.acme:8000/v1 model=qwen3-32b turn-timeout=10m
 * </pre>
 *
 * <p>Every field is optional; an unset one falls back to the deployment-wide {@code llm.*} defaults
 * on the {@code datahub-console} secret. It says which model and how to reach it — how much a turn
 * may spend stays deployment-wide.
 */
@Data
@NoArgsConstructor
public class TenantLlm {

    private LlmProvider provider;

    /** Required for Anthropic; optional for a self-hosted server, which may need none. */
    @JsonProperty("api-key")
    private String apiKey;

    private String model;

    /** Required for OpenAI-compatible, e.g. {@code http://localhost:11434/v1}. */
    @JsonProperty("base-url")
    private String baseUrl;

    /** OpenAI-compatible only. See {@code ChatProperties#reasoningEffort} for the three modes. */
    @JsonProperty("reasoning-effort")
    private String reasoningEffort;

    /**
     * A string, not a {@link Duration}, so both {@code 10m} and {@code PT10M} parse: these arrive
     * through Jackson, which understands only the latter.
     */
    @JsonProperty("turn-timeout")
    private String turnTimeout;

    /** The parsed {@link #turnTimeout}, or null when unset or unparseable. */
    @JsonIgnore
    public Duration getTurnTimeoutDuration() {
        if (turnTimeout == null || turnTimeout.isBlank()) {
            return null;
        }
        try {
            return DurationStyle.detectAndParse(turnTimeout.strip());
        } catch (IllegalArgumentException e) {
            // A typo in one optional field must not take a tenant's assistant down.
            return null;
        }
    }
}
