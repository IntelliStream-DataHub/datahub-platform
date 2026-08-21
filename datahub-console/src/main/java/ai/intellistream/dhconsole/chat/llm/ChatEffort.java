// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import java.util.Locale;

/**
 * How much thinking and tool work the model should spend on one turn.
 *
 * <p>Chosen per message: a lookup ("how many events yesterday?") does not need what a genuine
 * investigation ("why did these two series diverge?") does, and the person asking is the only one
 * who knows which it is. The deployment-wide {@code datahub.chat.effort} sets the default the
 * picker starts on.
 *
 * <p>The levels mirror the Anthropic API's {@code output_config.effort} one-for-one, which is the
 * only provider that implements them natively — {@link OpenAiCompatibleLlmClient} narrows the five
 * onto the three values the OpenAI chat-completions shape defines.
 *
 * <h3>Why the levels suggest an output budget</h3>
 * {@code max_tokens} caps thinking <em>and</em> response text together, so raising effort without
 * raising the ceiling buys deeper reasoning and then truncates the answer that reasoning produced.
 * Each level therefore names the budget it wants — but only as the default when nobody has said
 * otherwise. A configured {@code datahub.chat.max-output-tokens} always wins: setting a low roof is
 * a statement about money, and no picker in the UI should be able to overrule it. See
 * {@code ChatProperties#maxOutputTokensFor}.
 */
public enum ChatEffort {

    LOW(4_096),
    MEDIUM(4_096),
    HIGH(4_096),
    XHIGH(16_000),
    MAX(32_000);

    /** The API's own default, and the one a deployment gets if it configures nothing. */
    public static final ChatEffort DEFAULT = HIGH;

    private final int defaultOutputTokens;

    ChatEffort(int defaultOutputTokens) {
        this.defaultOutputTokens = defaultOutputTokens;
    }

    /**
     * Lenient by design: this parses a value that arrived from a browser, so
     * {@code xhigh}/{@code XHIGH}/{@code x-high} all land on the same level and anything
     * unrecognised falls back rather than failing the turn.
     */
    public static ChatEffort parse(String raw, ChatEffort fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT).replace("-", "").replace("_", ""));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** The value the Anthropic and OpenAI-compatible wires both spell in lower case. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The OpenAI chat-completions {@code reasoning_effort} vocabulary has three values, so the two
     * deep levels collapse onto {@code high}. Lossy, and deliberately so — a self-hosted server
     * that rejects an unknown value is worse than one that thinks slightly less than asked.
     */
    public String openAiReasoningEffort() {
        return switch (this) {
            case LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH, XHIGH, MAX -> "high";
        };
    }

    /**
     * The output budget this level wants, used only when no roof is configured. Never applied over
     * a configured one — see {@code ChatProperties#maxOutputTokensFor}.
     */
    public int defaultOutputTokens() {
        return defaultOutputTokens;
    }
}
