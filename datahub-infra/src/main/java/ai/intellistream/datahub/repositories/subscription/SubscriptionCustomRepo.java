// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.repositories.subscription;

import ai.intellistream.datahub.jpa.domains.SubscriptionEntity;
import ai.intellistream.datahub.models.paging.PageCursor;
import ai.intellistream.datahub.subscription.SubscriptionFilter;

import java.util.List;

/**
 * The Criteria-backed filter query behind {@code POST /subscriptions/filter}, mirroring
 * {@code DataSetCustomRepo} and {@code TimeseriesCustomRepo}.
 *
 * <p>Replaces the four hand-written derived queries this repository used to carry — one per
 * combination of "filtered by timeseries or not" and "system-managed included or not". Every
 * criterion added to {@link SubscriptionFilter} would have doubled that set again, which is the
 * shape the node repositories moved away from for the same reason.
 */
public interface SubscriptionCustomRepo {

    /**
     * Subscriptions matching every supplied criterion, in {@code sort} order, resuming after
     * {@code cursor} when one is given.
     *
     * @param filter     the criteria; null places no restriction beyond hiding system-managed rows
     * @param maxResults the page size, already clamped by the retriever
     * @param sort       the resolved order — never null; use {@link SubscriptionSort#DEFAULT}
     * @param cursor     where the previous page stopped, or null to start from the beginning
     */
    List<SubscriptionEntity> filter(SubscriptionFilter filter, int maxResults, SubscriptionSort sort, PageCursor cursor);
}
