// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.tenant;

/**
 * The model your tenant's AI assistant runs on, as returned by {@code GET /tenant/settings/llm}.
 *
 * <p>The credential is never returned. {@link #apiKeySet} says whether one is stored, which is all
 * a form needs to render "•••••• (unchanged)" against a field the user may leave alone. Everything
 * else is echoed as written, so a client can show and edit it.
 *
 * @param provider         {@code anthropic} or {@code openai-compatible}
 * @param model            model name as the provider spells it
 * @param baseUrl          OpenAI-compatible only, e.g. {@code http://localhost:11434/v1}
 * @param reasoningEffort  OpenAI-compatible only; unset sends nothing
 * @param effort           where the assistant's effort picker starts
 * @param turnTimeout      how long one turn may take, e.g. {@code 10m}
 * @param maxOutputTokens  hard ceiling on one call's output, or null to let the effort level choose
 * @param maxIterations    ceiling on model-to-tool-to-model round trips in one turn
 * @param instructions     appended to the built-in system prompt, never replacing it
 * @param apiKeySet        whether a credential is stored; the credential itself is never sent
 * @param configured       whether this amounts to a model that can actually be called, which is
 *                         what decides whether the tenant has an assistant at all
 */
public record TenantLlmSettings(String provider,
                                String model,
                                String baseUrl,
                                String reasoningEffort,
                                String effort,
                                String turnTimeout,
                                Integer maxOutputTokens,
                                Integer maxIterations,
                                String instructions,
                                boolean apiKeySet,
                                boolean configured) {

    /**
     * The effort levels {@link #effort} accepts, weakest first. Part of the contract because a
     * client rendering a picker has to know them.
     *
     * <p>The console owns the enum these mirror, and a test there fails if the two lists drift.
     * They cannot simply share it: the enum carries per-level token budgets and reasoning-effort
     * mapping, which are console concerns and would drag its chat internals into this library.
     */
    public static final java.util.List<String> EFFORT_LEVELS =
            java.util.List.of("low", "medium", "high", "xhigh", "max");

    /** The two providers {@link #provider} accepts. */
    public static final java.util.List<String> PROVIDERS =
            java.util.List.of("anthropic", "openai-compatible");

    /** What a tenant that has configured nothing looks like. */
    public static TenantLlmSettings none() {
        return new TenantLlmSettings(null, null, null, null, null, null, null, null, null,
                false, false);
    }
}
