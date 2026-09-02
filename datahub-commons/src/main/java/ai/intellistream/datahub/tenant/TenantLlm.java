// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;

/**
 * A tenant's LLM configuration, loaded from its Vault entry under the {@code llm} key:
 *
 * <pre>
 * "llm": { "provider": "anthropic", "api-key": "sk-ant-...", "model": "claude-opus-5" }
 * </pre>
 *
 * <p>or, for a tenant running its own model:
 *
 * <pre>
 * "llm": { "provider": "openai-compatible", "base-url": "http://vllm.acme:8000/v1",
 *          "model": "qwen3-32b", "reasoning-effort": "none", "turn-timeout": "10m" }
 * </pre>
 *
 * <p><strong>One credential per tenant.</strong> Every agent that tenant runs bills to this key,
 * which is what makes usage attributable to a customer without a reconciliation step. There is
 * deliberately no way to give one agent a different credential from another: that would be a
 * second billing relationship inside one tenant, and nothing has asked for one.
 *
 * <p><strong>It says which model and how to reach it — nothing about how much to spend on
 * it.</strong> Effort and the output-token roof are per-agent (the {@code agent} table), because
 * they are cost dials an operator wants to turn without touching a secret store, and because one
 * tenant's agents should not be forced to share a budget just because they share a key.
 *
 * <p>Deliberately <strong>not</strong> in {@code datahub-api-model}: {@link TenantFeatures} lives
 * there and is serialized verbatim out of {@code GET /tenant/features} to any API client. An api
 * key must never be reachable from a wire-contract type.
 *
 * <p>Every field is nullable. Unset means "not stated for this tenant", and the deployment-wide
 * default from {@code ChatProperties} applies — the same rule {@link TenantFeatures} uses for its
 * flags.
 */
@Data
@NoArgsConstructor
public class TenantLlm {

    /** Which wire protocol to speak. Unset falls back to the deployment default. */
    private LlmProvider provider;

    /**
     * Credential. Required by {@link LlmProvider#ANTHROPIC}; optional for
     * {@link LlmProvider#OPENAI_COMPATIBLE}, where Ollama needs none and vLLM behind a gateway
     * usually does.
     */
    @JsonProperty("api-key")
    private String apiKey;

    private String model;

    /** Required by {@link LlmProvider#OPENAI_COMPATIBLE}, e.g. {@code http://localhost:11434/v1}. */
    @JsonProperty("base-url")
    private String baseUrl;

    /**
     * What to send as {@code reasoning_effort} on the OpenAI-compatible path — a property of the
     * server, not of the agent, which is why it sits here and effort does not. Blank sends
     * nothing, {@code mapped} narrows the agent's effort onto the three values that wire defines,
     * anything else is sent verbatim. See {@code ChatProperties#reasoningEffort}.
     */
    @JsonProperty("reasoning-effort")
    private String reasoningEffort;

    /**
     * How long one turn may take against this backend, as a string so both {@code 10m} and
     * {@code PT10M} are accepted — Vault values arrive through Jackson, which understands only
     * the latter, while every other duration in this codebase is written the former way.
     *
     * <p>A hosted model wants seconds and a thinking model on CPU wants minutes, so this belongs
     * to the backend rather than the agent.
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
            // Fall back to the deployment default rather than failing every turn for this tenant:
            // a typo in one optional field should not take their assistant down.
            return null;
        }
    }
}
