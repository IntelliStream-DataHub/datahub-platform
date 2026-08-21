// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.event;

/**
 * A distinct (data_set_id, value) pair for one of the event categorical dimensions
 * (type / sub_type / status / source). Used both when upserting from the write path and when
 * rebuilding the dimension tables from the ClickHouse source during reconciliation.
 *
 * <p>{@code dataSetId} uses {@code 0} as the "no dataset" sentinel, matching the ClickHouse
 * {@code events.data_set_id} encoding.
 */
public record DatasetValue(long dataSetId, String value) {
}
