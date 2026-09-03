// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.config;

import ai.intellistream.dhconsole.chat.llm.ChatEffort;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Deployment-wide chat defaults: what a tenant gets when it has configured a model but said
 * nothing about how to run it.
 *
 * <p><strong>Which model, and on whose credential, is not here.</strong> That comes only from the
 * tenant's own {@code tenant-config} secret ({@code TenantLlm}) — there is no house key to fall back
 * to, so a tenant that configures nothing gets no assistant instead of one billed to the deployment.
 *
 * <p><strong>Nor are these ceilings.</strong> A tenant brings its own credential and pays its own
 * bill, so it may override every value below. The two exceptions are {@link #maxToolResultChars}
 * and {@link #maxMessages}, which bound the transcript this deployment serialises into its own
 * session store on every request — that is the platform's memory, not the tenant's spend.
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

    /**
     * Standing instructions appended to the built-in system prompt, for tenants that supply none of
     * their own. Appended rather than replacing, so the tool discipline and read-only framing cannot
     * be configured away.
     */
    private String instructions;

    /**
     * The effort level the picker starts on, and the level used when a request names none. Users
     * override it per message; this only sets where they start, for a tenant that named none.
     */
    private ChatEffort effort = ChatEffort.DEFAULT;

    /**
     * How long one user turn may take, end to end.
     *
     * <p>The panel gives up on the request after this, and the OpenAI-compatible client waits this
     * long for a single call: a call cannot legitimately outlast the turn it belongs to.
     *
     * <p>This is where a tenant that says nothing lands. Four minutes is generous for a hosted model and far too tight for a self-hosted one. A
     * thinking model on CPU spends 30s to 3min per call and the loop makes several, so a turn there
     * runs for minutes; raise this rather than cutting {@code maxIterations} down to where the
     * assistant cannot finish its work. Note the browser has its own response timeout, which caps
     * this regardless of what is configured here.
     */
    private Duration turnTimeout = Duration.ofMinutes(4);

    /** Default ceiling on model→tool→model round trips in one user turn. Bounds cost and latency. */
    private int maxIterations = 6;

    /**
     * Hard ceiling on one call's output, or unset to let the effort level choose.
     *
     * <p>{@code Integer} rather than {@code int} so "unset" is distinguishable from a value: unset
     * means the level decides (4096, rising to 16k at {@code xhigh} and 32k at {@code max}, because
     * {@code max_tokens} caps thinking and answer together and 4096 truncates a turn that thought
     * hard). Set, it is obeyed at every level.
     *
     * <p>The asymmetry is deliberate: the roof is written down once and the picker is clicked per
     * message, so the roof is the more considered of the two. Its cost is truncation, which is
     * visible, rather than a surprising bill, which is not — but leaving it unset is a real choice,
     * and a tenant paying its own way may make it.
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
