// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.subscription;

import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.subscription.SubscriptionFilter;

import java.util.Collection;
import java.util.List;

/**
 * The Criteria-backed filter query behind {@code POST /subscriptions/filter}, mirroring
 * {@code DataSetCustomRepo} and {@code TimeseriesCustomRepo}.
 *
 * <p>Replaces the four hand-written derived queries this repository used to carry — one per
 * combination of "filtered by timeseries or not" and "system-managed included or not". Every
 * criterion added to {@link SubscriptionFilter} would have doubled that set again, which is the
 * shape the node repositories moved away from for the same reason. None of them narrowed by the
 * caller's dataset grants either; that is now a parameter rather than another pair of methods.
 */
public interface SubscriptionCustomRepo {

    /**
     * Subscriptions matching every supplied criterion, in {@code sort} order, resuming after
     * {@code cursor} when one is given.
     *
     * @param filter             the criteria; null places no restriction
     * @param readableDataSetIds the caller's dataset grants, or null when they may read every
     *                           dataset. Never empty — a caller with no grants sees nothing, which
     *                           the service answers without running a query. Mirrors the
     *                           {@code dataSetIds} parameter of {@code TimeseriesCustomRepo.filter}
     * @param maxResults         the page size, already clamped by the retriever
     * @param sort               the resolved order — never null; use {@link SubscriptionSort#DEFAULT}
     * @param cursor             where the previous page stopped, or null to start from the beginning
     */
    List<SubscriptionEntity> filter(SubscriptionFilter filter, Collection<Long> readableDataSetIds,
                                    int maxResults, SubscriptionSort sort, PageCursor cursor);
}
