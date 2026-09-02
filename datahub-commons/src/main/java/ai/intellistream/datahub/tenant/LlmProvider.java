// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * Which LLM wire protocol a backend speaks.
 *
 * <p>Lives here rather than in the console because two sources have to agree on it: the
 * deployment-wide default, bound by Spring from {@code datahub.chat.provider}, and a tenant's
 * own backend, deserialized by Jackson from Vault. Spring's relaxed binding accepts
 * {@code openai-compatible} for {@link #OPENAI_COMPATIBLE} on its own; Jackson does not, hence
 * {@link #parse}.
 */
public enum LlmProvider {

    /** The Anthropic Messages API. */
    ANTHROPIC,

    /**
     * Any server speaking the OpenAI chat-completions API — Ollama, vLLM, llama.cpp, LM Studio.
     * This is the airgapped path: a {@code base-url} is required, an api key is not.
     */
    OPENAI_COMPATIBLE;

    /**
     * Lenient parse, so a Vault secret may say {@code anthropic}, {@code openai-compatible} or
     * {@code OPENAI_COMPATIBLE} interchangeably. Hyphens and spaces read as underscores, matching
     * what Spring's relaxed binding already accepts for the deployment default.
     *
     * @throws IllegalArgumentException on an unrecognised name — a misconfigured provider must be
     *                                  loud, since the alternative is silently answering from the
     *                                  wrong model or none at all
     */
    @JsonCreator
    public static LlmProvider parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return LlmProvider.valueOf(normalized);
    }
}
