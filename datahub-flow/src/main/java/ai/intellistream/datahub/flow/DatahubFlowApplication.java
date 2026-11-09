// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow;

import ai.intellistream.datahub.clickhouse.ClickHouseClientPool;
import ai.intellistream.datahub.config.BaseConfig;
import ai.intellistream.datahub.config.MetricsTlsVaultSecrets;
import ai.intellistream.datahub.config.PulsarConfig;
import ai.intellistream.datahub.config.PulsarVaultSecrets;
import ai.intellistream.datahub.config.VaultConfigurationLoader;
import ai.intellistream.datahub.flow.config.FlowVaultSecrets;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantConfigWriter;
import ai.intellistream.datahub.tenant.TenantLlmStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * intellistream-flow: runs flows, small pipelines of processors that read DataHub data, transform
 * it and write it back, on a schedule or on request. Any number of identical instances share the
 * work through rows in each tenant's own Postgres. See {@code INTELLISTREAM_FLOW_PLAN.md}.
 *
 * <p>Component scanning stays inside this module. From commons it takes only the tenant registry
 * ({@code TenantConfigService}, and {@code TenantLlmStore} which it is built with) and the
 * {@code VaultProperties} bean, imported one by one, with {@code TenantConfigWriter} for reading the
 * tenant's flow settings (the names of its secrets), and from infra the Pulsar client and admin
 * ({@code PulsarConfig}) and the per-tenant ClickHouse clients the run records are stored and read
 * with ({@code ClickHouseClientPool}); infra's configuration package is not scanned, so its JPA,
 * Neo4j and Valkey wiring stays off. Scheduling is enabled for the registry's five-minute refresh,
 * which is also what announces a tenant added while the service runs.
 */
@SpringBootApplication
@EnableScheduling
@Import({BaseConfig.class, TenantConfigService.class, TenantLlmStore.class, TenantConfigWriter.class,
		PulsarConfig.class, ClickHouseClientPool.class})
public class DatahubFlowApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(DatahubFlowApplication.class);
		// Registered here (not in spring.factories) so it runs on real startup but not in tests.
		app.addListeners(new VaultConfigurationLoader(new FlowVaultSecrets(), new PulsarVaultSecrets(),
				new MetricsTlsVaultSecrets(MetricsTlsVaultSecrets.MANAGEMENT_SSL)));
		app.run(args);
	}
}
