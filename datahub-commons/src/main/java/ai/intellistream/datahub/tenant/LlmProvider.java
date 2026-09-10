// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * Which LLM wire protocol a model speaks.
 *
 * <p>Shared because two sources must agree on it: the deployment default, bound by Spring's
 * relaxed binding, and a tenant's Vault entry, deserialized by Jackson — which is why {@link #parse}
 * exists, since only the former accepts {@code openai-compatible} on its own.
 */
public enum LlmProvider {

    /** The Anthropic Messages API. */
    ANTHROPIC,

    /** Ollama, vLLM, llama.cpp — the airgapped path. Needs a base-url, not a key. */
    OPENAI_COMPATIBLE;

    /**
     * Lenient, because these are hand-written into Vault.
     *
     * @throws IllegalArgumentException on an unrecognised name: the alternative is silently
     *                                  answering from the wrong model
     */
    @JsonCreator
    public static LlmProvider parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return LlmProvider.valueOf(normalized);
    }

    /**
     * The spelling written to Vault and shown to a client: lower case with hyphens, the form
     * {@link #parse} is most likely to be handed back.
     */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
