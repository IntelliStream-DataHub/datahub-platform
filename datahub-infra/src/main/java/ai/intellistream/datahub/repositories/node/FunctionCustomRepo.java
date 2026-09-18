// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.node;

import ai.intellistream.datahub.jpa.domains.FunctionEntity;
import ai.intellistream.datahub.models.datafilters.FunctionFilter;
import ai.intellistream.datahub.models.paging.PageCursor;

import java.util.Collection;
import java.util.List;

/**
 * The structured query behind {@code POST /functions/filter} and {@code POST /functions/search}.
 *
 * <p>Shaped like {@link TimeseriesCustomRepo} and {@link DataSetCustomRepo} rather than routed
 * through the generic node query: a function is a node type of its own, so its query pins its own
 * discriminator. The criteria themselves are all shared — a function adds no filterable field of
 * its own — so this is {@link NodePredicateBuilder} plus {@code NodeType.FUNCTION}, the same way
 * {@link DataSetCustomRepo} is.
 */
public interface FunctionCustomRepo {

    /**
     * Structured AND-combined function filter. Every attribute of {@code criteria} is optional; an
     * unset one places no restriction, so an empty filter is "every function the scope allows,
     * newest first, capped at {@code maxResults}".
     *
     * @param dataSetIds the expanded, ACL-intersected data set scope; {@code null} places no
     *                   restriction, empty returns nothing without running a query
     */
    List<FunctionEntity> filter(int maxResults, Collection<Long> dataSetIds, FunctionFilter criteria,
                                NodeSort sort, PageCursor cursor);

    /**
     * The same query with a full-text phrase ANDed on. Search is this filter plus one predicate, in
     * one query — see {@link FtsMatchFunctionContributor}.
     *
     * @param searchPhrase the phrase; {@code null} or blank places no text restriction, which makes
     *                     this identical to {@link #filter}
     */
    List<FunctionEntity> search(String searchPhrase, int maxResults, Collection<Long> dataSetIds,
                                FunctionFilter criteria);
}
