// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.api.services.LiveIngestCounter;
import ai.intellistream.datahub.clickhouse.ClickHouseDashboardService;
import ai.intellistream.datahub.services.ValkeyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One {@link LiveIngestCounter} bean per fast-moving dashboard tally, each with its own Valkey key
 * prefix and ClickHouse source-of-truth supplier. Explicit {@code @Bean} methods rather than
 * component-scanning {@link LiveIngestCounter} itself, since we need two independent instances (own
 * in-memory state, own {@code @Scheduled} flush/reconcile) of the same class.
 */
@Configuration
public class LiveIngestCounterConfig {

    @Bean
    public LiveIngestCounter datapointIngestCounter(ValkeyService valkeyService, ClickHouseDashboardService dashboardClickHouse) {
        return new LiveIngestCounter("dh:live:datapoints:", "datapoints", valkeyService, dashboardClickHouse::countAllDatapoints);
    }

    @Bean
    public LiveIngestCounter eventIngestCounter(ValkeyService valkeyService, ClickHouseDashboardService dashboardClickHouse) {
        return new LiveIngestCounter("dh:live:events:", "events", valkeyService, dashboardClickHouse::countEvents);
    }
}
