// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.NodeType;
import ai.intellistream.datahub.jpa.domains.TimeseriesEntity;
import ai.intellistream.datahub.models.datafilters.TimeseriesFilter;
import ai.intellistream.datahub.models.paging.PageCursor;

import java.util.Collection;
import java.util.List;

public interface TimeseriesCustomRepo {

    /**
     * Full-text search over timeseries, narrowed by the same {@link TimeseriesFilter} that
     * {@link #filter} takes, in a single query — search is that filter plus one predicate.
     *
     * <p>It used to be two queries: a native full-text query capped at a candidate ceiling, then
     * the filter re-asked about the ids it returned. See {@code FtsMatchFunctionContributor}.
     *
     * @param searchPhrase  the phrase; null or blank places no text restriction
     * @param dataSetIds    the expanded, ACL-intersected data set scope; null places no
     *                      restriction, empty returns nothing
     * @param criteria      the rest of the filter; null places no restriction
     */
    List<TimeseriesEntity> search(String searchPhrase, int maxResults, Collection<Long> dataSetIds,
                                  TimeseriesFilter criteria);

    <T> List<T> list(int maxResults, Class<T> type);
    <T> List<T> list(int maxResults, NodeType nodeType, Class<T> type);

    List<TimeseriesEntity> list(int maxResults);

    List<TimeseriesEntity> list(int maxResults, Collection<Long> allowedDataSetIds);

    /**
     * Structured AND-combined filter. Every parameter is optional: {@code null} (or blank for the
     * strings) means "no restriction on this attribute". {@code dataSetIds == null} means no
     * dataset restriction at all — callers with a narrowed view pass the ids they may read, and
     * must short-circuit on an empty set before calling.
     */
    List<TimeseriesEntity> filter(int maxResults, Collection<Long> dataSetIds, TimeseriesFilter criteria);

    /** The same, in a given order and continuing from a cursor. */
    List<TimeseriesEntity> filter(int maxResults, Collection<Long> dataSetIds, TimeseriesFilter criteria,
                                  NodeSort sort, PageCursor cursor);
}

