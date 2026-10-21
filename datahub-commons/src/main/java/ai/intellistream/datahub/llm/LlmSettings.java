// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.llm;

import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.datahub.tenant.TenantLlm;

import java.time.Duration;

/**
 * Everything one turn needs about the model it runs on: which model, on whose credential, and how
 * much it may spend getting there. The model comes only from the tenant; the rest is the tenant's
 * where it said something and the caller's default where it did not.
 *
 * <p>There is no deployment-wide model to inherit, so {@link #of} cannot quietly hand a tenant
 * someone else's credential: an unconfigured tenant has no settings, and whoever calls has checked
 * {@link TenantLlm#isUsable()} first. Nor are the defaults ceilings: a tenant on its own credential
 * pays its own bill and may spend it however it likes.
 */
public record LlmSettings(LlmProvider provider,
                          String apiKey,
                          String model,

                          /** OpenAI-compatible only. */
                          String baseUrl,

                          /** OpenAI-compatible only. */
                          String reasoningEffort,

                          /** How long one model call may take; the OpenAI-compatible and Anthropic clients both bound a request by it. */
                          Duration turnTimeout,

                          /** A configured roof, or null to let the effort level choose. */
                          Integer maxOutputTokens,

                          /** Where the panel's picker starts, and the level an unattended run uses. */
                          LlmEffort defaultEffort,

                          /** Ceiling on model to tool to model round trips in one turn. */
                          int maxIterations,

                          /** Appended to the built-in system prompt, never replacing it. */
                          String instructions) {

    /**
     * What a tenant gets for everything it left unsaid: the deployment's defaults for how a model
     * is run. Not ceilings — the tenant may override each of them.
     */
    public record Defaults(LlmEffort effort, Duration turnTimeout, int maxIterations,
                           Integer maxOutputTokens, String instructions) {
    }

    /**
     * The tenant's model, and its own values over the defaults for everything else. Vault fields
     * are hand-written, so a stray space is not a different credential: every string is stripped
     * and a blank one counts as unset.
     */
    public static LlmSettings of(TenantLlm llm, Defaults defaults) {
        return new LlmSettings(
                llm.getProvider(),
                strip(llm.getApiKey()),
                strip(llm.getModel()),
                strip(llm.getBaseUrl()),
                strip(llm.getReasoningEffort()),
                llm.getTurnTimeoutDuration() != null
                        ? llm.getTurnTimeoutDuration() : defaults.turnTimeout(),
                llm.getMaxOutputTokensValue() != null
                        ? llm.getMaxOutputTokensValue() : defaults.maxOutputTokens(),
                LlmEffort.parse(llm.getEffort(), defaults.effort()),
                llm.getMaxIterationsValue() != null
                        ? llm.getMaxIterationsValue() : defaults.maxIterations(),
                strip(llm.getInstructions()) != null
                        ? llm.getInstructions().strip() : defaults.instructions());
    }

    private static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Never the credential: this is passed around per turn and is one log line from leaking it. */
    @Override
    public String toString() {
        return "LlmSettings[provider=" + provider + ", model=" + model + ", baseUrl=" + baseUrl
                + ", turnTimeout=" + turnTimeout + ", apiKey=" + (apiKey == null ? "unset" : "***") + "]";
    }

    /**
     * A configured roof beats the level the user picked: the roof is written down once and the
     * picker is clicked per message, so the roof is the more considered of the two. Leaving it
     * unset is a real option — the effort level then chooses, and max effort costs what it costs.
     */
    public int maxOutputTokensFor(LlmEffort effort) {
        return maxOutputTokens != null ? maxOutputTokens : effort.defaultOutputTokens();
    }

    /**
     * What a cached client may be shared across. The model is absent on purpose — it is a
     * per-request parameter, so tenants on one credential share a connection pool.
     */
    public BackendKey backendKey() {
        return new BackendKey(provider, apiKey, baseUrl);
    }

    /** @see #backendKey() */
    public record BackendKey(LlmProvider provider, String apiKey, String baseUrl) {

        /** Records print every component, and this one is a cache key that ends up in log lines. */
        @Override
        public String toString() {
            return "BackendKey[provider=" + provider + ", baseUrl=" + baseUrl + ", apiKey=***]";
        }
    }
}
