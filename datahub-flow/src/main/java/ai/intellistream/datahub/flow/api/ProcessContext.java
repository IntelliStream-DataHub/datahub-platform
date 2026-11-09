// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.api;

import ai.intellistream.datahub.sdk.client.DatahubClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a processor is invoked with: its resolved property values, and what it may know about the
 * run.
 *
 * <p>Property values with only run-level references ({@code ${parameters.…}}, {@code ${run.…}},
 * {@code ${secrets.…}}) are already resolved. A value that refers to the item is resolved per item,
 * with {@link PropertyValue#evaluate(Item)}.
 */
public interface ProcessContext extends PropertyValues {

    /** The processor's id in the flow definition, e.g. {@code src}. */
    String processorId();

    /** The range of data time the run covers, {@code [start, end)}; empty for a run without one. */
    Optional<TimeWindow> window();

    /** The flow's parameters, as resolved for this run. */
    Map<String, Object> parameters();

    /**
     * The value of a secret from the tenant's flow settings. Never log it, put it in an attribute or
     * put it in a transit URI.
     */
    String secret(String name);

    /**
     * The flow's dataset: what decides who sees the flow, and where sinks write when they are not
     * told otherwise. Null only for a run of a definition that names none.
     */
    Long dataSetId();

    /**
     * The run's way into DataHub, acting as the run's identity: datahub-api applies that identity's
     * dataset access to every call, as for any other caller. Every call is bounded in time, and
     * once the run has lost its lease the client refuses to send anything, so a write never lands
     * after another instance has taken the run over.
     *
     * @throws ProcessException for a run that was given no client
     */
    DatahubClient datahub();

    /**
     * For a flow whose trigger is {@code events}, the events that trigger listens for: what a step that
     * reads the triggering events asks DataHub for. Empty for any other trigger.
     */
    Optional<EventMatch> triggerMatch();

    /** A preview: sinks report what they would write instead of writing it. */
    boolean isPreview();

    /**
     * Whether the run should stop: it was cancelled, ran past its timeout, or lost its lease. A
     * processor doing long work checks this between steps and throws when it is set.
     */
    boolean isCancelled();

    /** Messages that end up on the run's page. */
    RunLog log();

    UUID runId();

    /** Which attempt of the run this is, from 1: a processor that must not repeat itself looks. */
    int attempt();

    /** The flow's external id, as the definition names it; what a processor stamps as provenance. */
    String flowExternalId();

    /**
     * The run's current access token, for a processor that calls something other than DataHub as
     * the run's identity (an MCP server). Like {@link #secret}: never log it, put it in an attribute
     * or put it in a transit URI. Taken fresh on every call, because it is refreshed under the run
     * and refused once the run has lost its lease.
     *
     * @throws ProcessException for a run that was given none
     * @throws IllegalStateException once the run no longer holds its lease: nothing more may go out
     */
    String accessToken();
}
