// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.config;

import ai.intellistream.datahub.llm.LlmEffort;
import ai.intellistream.datahub.llm.LlmSettings;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Deployment-wide defaults for agent runs: what an agent gets when neither it nor its tenant said
 * how a model is to be run. Which model, and on whose credential, is never here: that is the
 * tenant's own {@code llm.*} settings, as for the console's chat, and a tenant without them has no
 * agents rather than agents billed to the deployment.
 *
 * <p>Not ceilings: a tenant on its own credential may set every value higher on its own settings
 * or on an agent. The two exceptions bound the platform's own memory, not the tenant's spend: the
 * tool result cap, which is what a result costs the model's context and the transcript, and the
 * transcript cap, which is what one run may leave in the tenant's KVRocks.
 *
 * <p>Smaller than the chat's: an agent runs unattended in one of the instance's run slots, so a
 * turn of two minutes and six of them bound a run to well under its {@code PT20M} timeout.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "datahub.flow.agents")
public class AgentProperties {

    /** The effort level a run uses when neither the agent nor its tenant named one. */
    private LlmEffort effort = LlmEffort.DEFAULT;

    /** How long one model call may take. */
    private Duration turnTimeout = Duration.ofMinutes(2);

    /** Ceiling on model to tool to model round trips in one run. */
    private int maxIterations = 6;

    /** Hard ceiling on one call's output, or unset to let the effort level choose. */
    private Integer maxOutputTokens;

    /** Per-tool-result cap, and the cap on the events message a run starts from. */
    private int maxToolResultChars = 24_000;

    /** What one invocation may write to its transcript before it is cut with a {@code truncated} row. */
    private DataSize transcriptMaxBytes = DataSize.ofMegabytes(1);

    /** What a tenant gets where it said nothing, in the shape {@link LlmSettings#of} takes. */
    public LlmSettings.Defaults defaults() {
        return new LlmSettings.Defaults(effort, turnTimeout, maxIterations, maxOutputTokens, null);
    }
}
