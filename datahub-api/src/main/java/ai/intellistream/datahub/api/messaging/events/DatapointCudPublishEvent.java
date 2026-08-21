// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.messaging.events;

import ai.intellistream.datahub.api.responses.DataWrapperBin;

/**
 * Signals that a {@link DataWrapperBin} should be published to the all-datapoints topic once the
 * enclosing JPA transaction commits. Fired by services inside their {@code @Transactional} methods
 * and consumed by {@code AfterCommitMessagePublisher} in an {@code AFTER_COMMIT} listener.
 *
 * <p>Used for the data-point purge that accompanies a timeseries delete: if the delete rolls back,
 * the timeseries still exists and its data-points must not be wiped, so the message has to wait for
 * the commit. Data-point <em>ingest</em> does not go through here — it is not part of a database
 * transaction and is sent straight to the producer on the hot path.
 */
public record DatapointCudPublishEvent(DataWrapperBin message) {
}
