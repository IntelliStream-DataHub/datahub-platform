// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

/**
 * What an ingest path needs to know about a series before it accepts datapoints for it: the dataset
 * the write is authorised against, the value type the payload must match, and the external id the
 * caller claimed. Four columns, read without hydrating the entity or its dataset.
 *
 * @param dataSetId null for an orphan series, which needs an all-datasets grant to write
 */
public record SeriesMeta(long id, String externalId, int valueTypeId, Long dataSetId) {
}
