// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers.errors;

/**
 * The tenant has reached a lifetime ceiling: the size of its sandbox, not a rate.
 *
 * <p>Waiting does not clear this, so it is a 403 with no {@code Retry-After} and a message saying
 * how it is lifted. The distinction matters to a client: the SDK retries a 429 and surfaces a 403,
 * which is the right treatment for each.
 */
public class TenantLimitReachedException extends LimitException {

    private final String metric;
    private final long limit;

    public TenantLimitReachedException(String metric, long limit) {
        super(("This tenant has reached its limit of %d %s. Contact IntelliStream to have it "
                + "raised.").formatted(limit, metric));
        this.metric = metric;
        this.limit = limit;
    }

    public String getMetric() {
        return metric;
    }

    public long getLimit() {
        return limit;
    }
}
