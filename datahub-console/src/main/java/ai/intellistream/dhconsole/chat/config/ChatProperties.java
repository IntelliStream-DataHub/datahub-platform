// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.datahub.tenant.LlmProvider;
import ai.intellistream.dhconsole.chat.llm.ChatEffort;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Deployment-wide chat configuration: what applies when a tenant has said nothing, and therefore
 * what a new tenant gets.
 *
 * <p><strong>Defaults belong here, not in {@code application.properties}.</strong>
 * {@code VaultConfigurationLoader} registers its property source with {@code addLast}, i.e. lowest
 * precedence, so a key present in {@code application.properties} would permanently shadow the
 * Vault value for that key.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "datahub.chat")
public class ChatProperties {

    /** Master switch. Off means no panel is rendered and the endpoints refuse. */
    private boolean enabled = false;

    /** Shared with {@code TenantLlm}, since both sources have to agree on the vocabulary. */
    private LlmProvider provider = LlmProvider.ANTHROPIC;

    private String model = "claude-sonnet-5";

    private String apiKey;

    /** Only used by the OpenAI-compatible provider (vLLM, Ollama, …). */
    private String baseUrl;

    /**
     * Standing instructions appended to the built-in system prompt — domain vocabulary, tag
     * conventions, what this customer cares about. Appended rather than replacing, so the tool
     * discipline and read-only framing cannot be configured away.
     */
    private String instructions;

    /**
     * The effort level the picker starts on, and the level used when a request names none. Users
     * override it per message; this only sets where they start.
     */
    private ChatEffort effort = ChatEffort.DEFAULT;

    /**
     * What to send as {@code reasoning_effort} on the OpenAI-compatible path.
     *
     * <ul>
     *   <li><b>blank</b> (the default) — send nothing. "OpenAI-compatible" is a family, not a
     *       specification, and a server that validates its request model strictly rejects a field it
     *       does not know, so this cannot be on by default.</li>
     *   <li><b>{@code mapped}</b> — send the level the user picked, narrowed by
     *       {@link ChatEffort#openAiReasoningEffort()} onto the three values that wire defines. For
     *       a server whose reasoning is genuinely graded — OpenAI, Grok, and others.</li>
     *   <li><b>anything else</b> — sent verbatim. This is how {@code none} is reachable: no effort
     *       level maps to it, because no Anthropic level means "do not think", and a self-hosted
     *       thinking model that spends its budget reasoning returns an empty answer, since this
     *       client reads only {@code content} and {@code tool_calls}.</li>
     * </ul>
     *
     * <p>One property rather than an on/off switch plus a value: with two, setting only the value
     * silently did nothing, which is exactly what someone reaching for {@code none} would try first.
     */
    private String reasoningEffort;

    /**
     * How long one user turn may take, end to end.
     *
     * <p>The panel gives up on the request after this, and the OpenAI-compatible client waits this
     * long for a single call: a call cannot legitimately outlast the turn it belongs to.
     *
     * <p>Four minutes is generous for a hosted model and far too tight for a self-hosted one. A
     * thinking model on CPU spends 30s to 3min per call and the loop makes several, so a turn there
     * runs for minutes; raise this rather than cutting {@code maxIterations} down to where the
     * assistant cannot finish its work. Note the browser has its own response timeout, which caps
     * this regardless of what is configured here.
     */
    private Duration turnTimeout = Duration.ofMinutes(4);

    /** Ceiling on model→tool→model round trips in one user turn. Bounds cost and latency. */
    private int maxIterations = 6;

    /**
     * Hard ceiling on one call's output, or unset to let the effort level choose.
     *
     * <p>{@code Integer} rather than {@code int} so "unset" is distinguishable from a value: unset
     * means the level decides (4096, rising to 16k at {@code xhigh} and 32k at {@code max}, because
     * {@code max_tokens} caps thinking and answer together and 4096 truncates a turn that thought
     * hard). Set, it is obeyed at every level.
     *
     * <p>The asymmetry is deliberate. A configured low roof is a statement about money — at Opus
     * pricing one {@code max}-effort turn can run to six calls of 32k output tokens — and the effort
     * picker is a per-message UI control. The setting an operator wrote down wins over the one a
     * user clicked; the cost of that is truncation, which is visible, rather than a surprising bill,
     * which is not.
     */
    private Integer maxOutputTokens;

    /**
     * Per-tool-result cap. A single {@code timeseries_fetch_datapoints} can return far more than
     * the conversation needs, and the transcript is serialised into the session on every request.
     */
    private int maxToolResultChars = 24_000;

    /** Transcript cap, trimmed oldest-exchange-first. */
    private int maxMessages = 40;

    /** The output ceiling for one call at this effort: what was configured, else what the level wants. */
    public int maxOutputTokensFor(ChatEffort effort) {
        return maxOutputTokens != null ? maxOutputTokens : effort.defaultOutputTokens();
    }
}
