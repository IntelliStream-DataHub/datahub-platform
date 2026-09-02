// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.tenant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A tenant's model configuration as a client may see it.
 *
 * <p><strong>The API key is not here and never will be.</strong> A settings form needs to show
 * whether a key is set, not what it is: there is no screen on which the value is useful, and every
 * way of returning it — a UI field, a browser cache, a support screenshot — is a way of leaking it.
 * {@link #hasApiKey} answers the only question anyone actually has.
 *
 * @param provider     {@code ANTHROPIC} or {@code OPENAI_COMPATIBLE}, or null to use the
 *                     deployment default
 * @param model        the model name, or null for the deployment default
 * @param baseUrl      only meaningful for an OpenAI-compatible server
 * @param hasApiKey    whether a credential is stored. False is normal for a self-hosted model
 * @param configured   false when this tenant has no configuration of its own at all and every
 *                     field above is simply the deployment default showing through
 */
@Schema(name = "TenantLlmView",
        description = """
                Your tenant's model configuration. The API key is never returned — hasApiKey says
                whether one is stored, which is the only thing a form needs to know.""")
public record TenantLlmView(
        @Schema(description = "Which model wire to speak.", example = "ANTHROPIC") String provider,
        @Schema(description = "Model name.", example = "claude-opus-5") String model,
        @Schema(description = "Endpoint, for an OpenAI-compatible server.",
                example = "http://vllm.acme:8000/v1") String baseUrl,
        @Schema(description = "reasoning_effort to send on the OpenAI-compatible path.")
        String reasoningEffort,
        @Schema(description = "How long one turn may take.", example = "10m") String turnTimeout,
        @Schema(description = "Whether an API key is stored. The key itself is never returned.")
        boolean hasApiKey,
        @Schema(description = "False when the tenant has no configuration of its own and is using "
                + "the deployment default.") boolean configured) {
}
