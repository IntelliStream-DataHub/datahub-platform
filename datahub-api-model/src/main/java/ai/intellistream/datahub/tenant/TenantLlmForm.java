// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.tenant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A change to a tenant's model configuration.
 *
 * <h2>How the key behaves</h2>
 * Omitting {@link #apiKey} (or sending null) <strong>leaves the stored key as it is</strong>. That
 * is what makes a settings form work at all: the form cannot show the current key, so a save that
 * treated "the field I could not populate" as "clear it" would delete the credential every time
 * anyone edited the model name.
 *
 * <p>Sending an empty string is the explicit way to clear it, for a tenant moving to a self-hosted
 * model that needs none. Deliberately different from null, because the two mean opposite things
 * and one of them is destructive.
 */
@Schema(name = "TenantLlmForm",
        description = """
                A change to your tenant's model configuration. Omit apiKey to leave the stored key
                unchanged; send an empty string to clear it. Every other field is replaced by what
                you send, so send the whole configuration you want.""")
public record TenantLlmForm(
        @Schema(description = "anthropic or openai-compatible. Null uses the deployment default.")
        String provider,
        @Schema(description = "Model name. Null uses the deployment default.") String model,
        @Schema(description = "Endpoint, required for an OpenAI-compatible server.") String baseUrl,
        @Schema(description = "reasoning_effort to send on the OpenAI-compatible path. Blank sends "
                + "nothing, 'mapped' sends the level the user picked, anything else is verbatim.")
        String reasoningEffort,
        @Schema(description = "How long one turn may take, e.g. 4m or PT4M.") String turnTimeout,
        @Schema(description = "New API key. Omit to keep the stored one; empty string to clear it.")
        String apiKey) {
}
