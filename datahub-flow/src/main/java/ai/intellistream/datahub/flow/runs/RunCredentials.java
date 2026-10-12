// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import java.util.function.Supplier;

/**
 * Whose access a run uses: the bearer tokens its DataHub client sends.
 *
 * <p>A run acts as the identity its flow is deployed with — the person who deployed it, through the
 * offline token they granted, or a registered client (the plan's <em>Who a run acts as</em>). The
 * service's implementation is {@code FlowRunCredentials}; the coordinator's tests supply their own.
 */
@FunctionalInterface
public interface RunCredentials {

    /**
     * A source of bearer tokens for one attempt of a run of flow {@code flowId}, called before every
     * request the run makes. It may refresh as it likes; whatever it throws reaches the processor
     * that made the request.
     *
     * @param tenantId the run's tenant
     * @param flowId   the run's flow, whose deployment names the identity
     * @throws RunIdentityException when the run has no usable identity; the run fails without
     *                              starting
     */
    Supplier<String> tokens(String tenantId, long flowId);

    /** The run cannot act as anyone. */
    class RunIdentityException extends RuntimeException {

        private final boolean rejected;

        /** Keycloak could not be asked: a later attempt may get past it. */
        public RunIdentityException(String message) {
            this(message, false);
        }

        /**
         * @param rejected Keycloak, or the deployment, refused the identity for good: no attempt
         *                 will get past it until someone deploys the flow afresh
         */
        public RunIdentityException(String message, boolean rejected) {
            super(message);
            this.rejected = rejected;
        }

        public boolean rejected() {
            return rejected;
        }
    }
}
