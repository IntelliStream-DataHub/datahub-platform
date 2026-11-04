// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.api;

import ai.intellistream.datahub.sdk.client.DatahubClient;

import java.util.Map;
import java.util.Optional;

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

    /** A preview: sinks report what they would write instead of writing it. */
    boolean isPreview();

    /**
     * Whether the run should stop: it was cancelled, ran past its timeout, or lost its lease. A
     * processor doing long work checks this between steps and throws when it is set.
     */
    boolean isCancelled();

    /** Messages that end up on the run's page. */
    RunLog log();
}
