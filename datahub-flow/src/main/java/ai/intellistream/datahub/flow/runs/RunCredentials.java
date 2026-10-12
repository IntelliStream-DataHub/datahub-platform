// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import java.util.function.Supplier;

/**
 * Whose access a run uses: the bearer tokens its DataHub client sends.
 *
 * <p>A run acts as the identity its flow was deployed with — the person who deployed it, through
 * the offline token they granted, or a registered client (the plan's <em>Who a run acts as</em>).
 * Getting those tokens is the next slice's work; this is the seam it plugs into. Until then the
 * service's only implementation is {@link UnconfiguredRunCredentials}, which fails every run with
 * {@link RunCoordinator#AUTH}, and the coordinator's tests supply their own.
 */
@FunctionalInterface
public interface RunCredentials {

    /**
     * A source of bearer tokens for one attempt of a run, called before every request the run makes.
     * It may refresh as it likes; whatever it throws reaches the processor that made the request.
     *
     * @param tenantId the run's tenant
     * @param runAs    the identity recorded on the run: the deployer's subject or a client's name
     * @throws RunIdentityException when the run has no usable identity; the run fails with
     *                              {@link RunCoordinator#AUTH} without starting
     */
    Supplier<String> tokens(String tenantId, String runAs);

    /** The run cannot act as anyone. */
    class RunIdentityException extends RuntimeException {
        public RunIdentityException(String message) {
            super(message);
        }
    }
}
