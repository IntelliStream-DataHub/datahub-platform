// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.tenant;

/**
 * A change to the model your tenant's AI assistant runs on, for {@code PUT /tenant/settings/llm}.
 *
 * <p>Separate from {@link TenantLlmSettings} rather than reusing it, because the credential travels
 * one way only: it can be written here and is never read back. One record carrying both would put
 * the field on the response too, one serialisation away from returning every tenant's key.
 *
 * <p><strong>{@link #apiKey} is three-valued.</strong> Absent (null) means leave the stored
 * credential alone — which is what a form submitted without retyping a masked field sends. Empty
 * means remove it. A value replaces it. Without the middle case there would be no way to clear a
 * key that is no longer valid; without the first, every save would demand the key again.
 *
 * <p>Everything else is replaced by what is sent, absent meaning unset. A field left unset falls
 * back to the deployment default, except the ones that identify the model — a provider, a model,
 * and an {@code apiKey} for Anthropic or a {@code baseUrl} for OpenAI-compatible — which have no
 * default and are what makes the assistant available at all.
 */
public record TenantLlmSettingsForm(String provider,
                                    String model,
                                    String apiKey,
                                    String baseUrl,
                                    String reasoningEffort,
                                    String effort,
                                    String turnTimeout,
                                    Integer maxOutputTokens,
                                    Integer maxIterations,
                                    String instructions) {
}
