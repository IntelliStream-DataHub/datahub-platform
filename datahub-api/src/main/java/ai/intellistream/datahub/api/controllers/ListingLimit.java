// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.models.datafilters.FilterDefaults;

/**
 * The {@code ?limit=} contract shared by every {@code GET /<collection>} listing.
 *
 * <p>Here rather than repeated per controller because these had already drifted the way the
 * retrievers did before {@code FilterDefaults} pulled them together: two node types had a listing
 * capped at 1000, two returned every row in the tenant with no cap at all, and three had no listing
 * to cap. The same trailing-slash route on {@code /timeseries} defaulted to 100 where the route it
 * delegates to defaulted to 1000, so the page size depended on whether the caller typed a slash.
 *
 * <p>One rule: absent, zero or negative means "you decide" and yields
 * {@link FilterDefaults#DEFAULT_LIMIT}; anything above {@link FilterDefaults#MAX_LIMIT} is a 400
 * rather than a silent clamp, because a caller who asked for 50 000 rows and received 10 000 has no
 * way to tell that from a tenant that only had 10 000. The same numbers {@code POST /filter} uses,
 * so which endpoint you reach for cannot change the page you get.
 */
final class ListingLimit {

    private ListingLimit() {
    }

    /**
     * The 400 response body for an out-of-range limit, or null when it is acceptable.
     *
     * <p>Returned rather than thrown: the listings answer a bad limit with a plain-text 400, which
     * is what {@code GET /timeseries} has always done and what their {@code @ApiResponse} promises.
     */
    static String rejection(Integer limit) {
        if (limit != null && limit > FilterDefaults.MAX_LIMIT) {
            return "limit: must be less than or equal to " + FilterDefaults.MAX_LIMIT;
        }
        return null;
    }

    /**
     * The effective page size, for the listings whose service takes a plain {@code int}.
     *
     * <p>Where a retriever is involved, prefer setting the limit on it and letting its own setter
     * apply this rule — {@code SubscriptionRetriever.setLimit} and the four beside it already do,
     * and going through them is what keeps the GET and the POST agreeing by construction rather
     * than by two copies of the same conditional.
     */
    static int resolve(Integer limit) {
        return (limit == null || limit <= 0) ? FilterDefaults.DEFAULT_LIMIT : limit;
    }
}
