// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

/**
 * The limits in force for one tenant: the deployment defaults with that tenant's overrides applied.
 *
 * <p>A value of 0 or below means unlimited, so a check reads the same way everywhere:
 * {@code if (limit > 0 && used > limit)}.
 */
public record TenantLimits(
        int writePerMinutePerTenant,
        int readPerMinutePerTenant,
        int writePerMinutePerUser,
        int readPerMinutePerUser,
        long eventsPerDay,
        long nodesPerDay,
        long edgesPerDay,
        long datapointsPerDay,
        long ingestBytesPerDay,
        long maxResources,
        long maxEventsTotal,
        long maxDatapointsTotal,
        long maxTextDatapointsTotal,
        int maxWsSocketsPerTenant,
        int maxWsSocketsPerUser) {

    public static boolean unlimited(long limit) {
        return limit <= 0;
    }
}
