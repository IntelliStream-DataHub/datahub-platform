// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.engine;

import ai.intellistream.datahub.flow.api.TimeWindow;
import ai.intellistream.datahub.sdk.client.DatahubClient;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * What one attempt of a run is executed with.
 *
 * @param window      the range of data time it covers, or null for a run without one
 * @param parameters  values for the flow's parameters, overriding their defaults: strings, numbers
 *                    ({@link java.math.BigDecimal}), booleans or lists, as JSON would give them
 * @param memoryBytes the cap on the run's Arrow memory; going over fails this run alone
 * @param secrets     a secret's value by name, or null when the tenant has no such secret
 * @param cancelled   whether the run should stop: cancelled, past its timeout, or its lease lost
 * @param dataSetId   the flow's dataset, or null for a definition that names none
 * @param datahub     the run's DataHub client, acting as its identity; null for a run that never
 *                    reaches DataHub
 */
public record RunRequest(UUID runId, int attempt, TimeWindow window, Map<String, Object> parameters,
                         boolean preview, long memoryBytes, Function<String, String> secrets,
                         BooleanSupplier cancelled, Long dataSetId, DatahubClient datahub) {

    public RunRequest {
        Objects.requireNonNull(runId, "runId");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        secrets = secrets == null ? name -> null : secrets;
        cancelled = cancelled == null ? () -> false : cancelled;
    }
}
