// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub;

import ai.intellistream.datahub.cleanup.file.FileCleanupProperties;
import ai.intellistream.datahub.cleanup.migration.MigrationSweepProperties;
import ai.intellistream.datahub.cleanup.subscription.SubscriptionCleanupProperties;
import ai.intellistream.datahub.config.PulsarVaultSecrets;
import ai.intellistream.datahub.config.MetricsTlsVaultSecrets;
import ai.intellistream.datahub.config.VaultConfigurationLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Headless scheduled application for DataHub housekeeping. Consolidates every cleanup job:
 * <ul>
 *   <li><b>file</b> ({@code cleanup.file}) — stale upload temp files and folders of removed
 *       tenants;</li>
 *   <li><b>subscription</b> ({@code cleanup.subscription}) — orphaned Pulsar subscriptions whose
 *       backlog would otherwise grow until it trips the fan-out namespace's backlog quota and
 *       stalls a tenant's live datapoint feed;</li>
 *   <li><b>event dimensions</b> ({@code cleanup.eventdimension}) — the weekly rebuild of the event
 *       type/sub_type/status/source tables from ClickHouse, which is the only thing that drops
 *       values whose last event is gone.</li>
 * </ul>
 *
 * <p>The subscription sweep needs Postgres (the {@code SubscriptionRepository}) and the Pulsar
 * admin client, so this app brings up datahub-infra's JPA + Pulsar config — including the
 * multi-tenant {@code StatelessRoutingDataSource}. Component scanning is kept to the packages those
 * beans live in ({@code config}, {@code tenant}, {@code pulsar}, {@code clickhouse}) plus this
 * module's {@code cleanup} package; the Neo4j and Valkey clients in {@code config} connect lazily
 * and stay unused.
 *
 * <p>Run as a SINGLE instance — two janitors deleting concurrently is wasteful and racy.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({FileCleanupProperties.class, SubscriptionCleanupProperties.class,
        MigrationSweepProperties.class})
@ComponentScan(basePackages = {
        "ai.intellistream.datahub.config",
        "ai.intellistream.datahub.tenant",
        "ai.intellistream.datahub.pulsar",
        // ClickHouseEventService: the event-dimension rebuild reads the authoritative DISTINCT
        // values from ClickHouse before rewriting the Postgres dimension tables.
        "ai.intellistream.datahub.clickhouse",
        "ai.intellistream.datahub.cleanup"
})
public class DatahubCleanupApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(DatahubCleanupApplication.class);
        app.addListeners(new VaultConfigurationLoader(new PulsarVaultSecrets(),
                new MetricsTlsVaultSecrets(MetricsTlsVaultSecrets.SERVER_SSL)));
        app.run(args);
    }
}
