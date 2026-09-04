// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;

/**
 * A tenant's model configuration, from the {@code llm.*} keys of its {@code tenant-config/<org-name>} secret:
 *
 * <pre>
 * provider=anthropic api-key=sk-ant-... model=claude-opus-5
 * provider=openai-compatible base-url=http://vllm.acme:8000/v1 model=qwen3-32b turn-timeout=10m
 * </pre>
 *
 * <p>This is the <em>only</em> source of a model: there is no deployment-wide credential to inherit,
 * so a tenant that has not configured one has no assistant rather than one billed to whoever
 * deployed the platform. {@link #isUsable()} is that test, and {@code ChatAccess} hides the panel
 * when it fails.
 *
 * <p>The spend is theirs too. A tenant on its own credential pays its own bill, so it sets its own
 * effort, token roof and iteration cap — running max effort with no roof is an expensive choice
 * this deliberately does not prevent. Only the four fields identifying the model are required;
 * anything else unset falls back to the deployment default in {@code ChatProperties}.
 */
@Data
@NoArgsConstructor
public class TenantLlm {

    private LlmProvider provider;

    /**
     * Required for Anthropic; a self-hosted server may need none.
     *
     * <p>Excluded from {@code toString()}: Lombok prints every field, so without this any
     * {@code log.debug("...{}", tenant)} anywhere would put the credential in the log.
     */
    @ToString.Exclude
    @JsonProperty("api-key")
    private String apiKey;

    private String model;

    /** Required for OpenAI-compatible, e.g. {@code http://localhost:11434/v1}. */
    @JsonProperty("base-url")
    private String baseUrl;

    /**
     * OpenAI-compatible only: what to send as {@code reasoning_effort}.
     *
     * <ul>
     *   <li><b>unset</b> — send nothing. "OpenAI-compatible" is a family, not a specification, and a
     *       server that validates its request model strictly rejects a field it does not know, so
     *       this cannot be on by default.</li>
     *   <li><b>{@code mapped}</b> — send the level the user picked, narrowed onto the three values
     *       that wire defines. For a server whose reasoning is genuinely graded.</li>
     *   <li><b>anything else</b> — sent verbatim. This is how {@code none} is reachable: no effort
     *       level maps to it, because no Anthropic level means "do not think", and a self-hosted
     *       thinking model that spends its budget reasoning returns an empty answer, since the
     *       client reads only {@code content} and {@code tool_calls}.</li>
     * </ul>
     */
    @JsonProperty("reasoning-effort")
    private String reasoningEffort;

    /**
     * A string, not a {@link Duration}, so both {@code 10m} and {@code PT10M} parse: these arrive
     * through Jackson, which understands only the latter.
     */
    @JsonProperty("turn-timeout")
    private String turnTimeout;

    /** Where this tenant's effort picker starts. Users still override it per message. */
    private String effort;

    /**
     * Hard ceiling on one call's output, or unset to let the effort level choose.
     *
     * <p>A string for the same reason the numbers below are: Vault stores strings, and a typo here
     * must cost this field rather than the whole entry — which, with nothing to fall back to, would
     * cost the tenant its assistant.
     */
    @JsonProperty("max-output-tokens")
    private String maxOutputTokens;

    /** Ceiling on model to tool to model round trips in one turn. */
    @JsonProperty("max-iterations")
    private String maxIterations;

    /**
     * Standing instructions appended to the built-in system prompt — this tenant's domain
     * vocabulary, tag conventions, what it cares about. Appended rather than replacing, so the tool
     * discipline and the read-only framing cannot be configured away.
     */
    private String instructions;

    /**
     * Whether this names a model that can actually be called: a provider, a model, and whichever of
     * credential or endpoint that provider reaches its model with.
     *
     * <p>Checked before the panel renders rather than on the first turn, so a half-written secret
     * shows as no assistant instead of one that greets the user and then fails.
     */
    @JsonIgnore
    public boolean isUsable() {
        if (provider == null || isBlank(model)) {
            return false;
        }
        return switch (provider) {
            case ANTHROPIC -> !isBlank(apiKey);
            // No key: Ollama and some vLLM deployments authenticate at the network edge, or not at all.
            case OPENAI_COMPATIBLE -> !isBlank(baseUrl);
        };
    }

    /** The parsed {@link #maxOutputTokens}, or null when unset or unparseable. */
    @JsonIgnore
    public Integer getMaxOutputTokensValue() {
        return positiveInt(maxOutputTokens);
    }

    /** The parsed {@link #maxIterations}, or null when unset or unparseable. */
    @JsonIgnore
    public Integer getMaxIterationsValue() {
        return positiveInt(maxIterations);
    }

    /** The parsed {@link #turnTimeout}, or null when unset or unparseable. */
    @JsonIgnore
    public Duration getTurnTimeoutDuration() {
        if (isBlank(turnTimeout)) {
            return null;
        }
        try {
            return DurationStyle.detectAndParse(turnTimeout.strip());
        } catch (IllegalArgumentException e) {
            // A typo in one optional field must not take a tenant's assistant down.
            return null;
        }
    }

    /** Null rather than an exception on anything unusable, including zero and negatives. */
    private static Integer positiveInt(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.strip());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
