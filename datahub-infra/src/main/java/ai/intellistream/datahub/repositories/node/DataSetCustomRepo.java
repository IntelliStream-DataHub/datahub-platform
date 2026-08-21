// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.DatasetEntity;
import ai.intellistream.datahub.models.datafilters.DataSetFilter;
import ai.intellistream.datahub.models.paging.PageCursor;

import java.util.List;

public interface DataSetCustomRepo {

    /**
     * Structured AND-combined dataset filter, mirroring the shape of
     * {@code TimeseriesCustomRepo.filter} and {@code ResourceService.filter}. Every attribute of
     * {@code filter} is optional; an unset one places no restriction, so an empty filter is
     * "every dataset, newest first, capped at {@code maxResults}".
     *
     * @param filter     criteria; {@code null} is treated as an empty filter
     * @param maxResults hard cap on rows returned
     */
    List<DatasetEntity> filter(DataSetFilter filter, int maxResults);

    /** The same, in a given order and continuing from a cursor. */
    List<DatasetEntity> filter(DataSetFilter filter, int maxResults, NodeSort sort, PageCursor cursor);

    /**
     * The same query with a full-text phrase ANDed on, backing {@code POST /datasets/search}.
     *
     * <p>Search is this filter plus one predicate, in one query. It used to be two: a native
     * full-text query capped at a candidate ceiling, then this filter re-asked about the ids it
     * returned. See {@link FtsMatchFunctionContributor}.
     *
     * @param searchPhrase the phrase; {@code null} or blank places no text restriction, making this
     *                     identical to {@link #filter(DataSetFilter, int, NodeSort, PageCursor)}
     */
    List<DatasetEntity> search(String searchPhrase, DataSetFilter filter, int maxResults,
                               NodeSort sort, PageCursor cursor);
}
