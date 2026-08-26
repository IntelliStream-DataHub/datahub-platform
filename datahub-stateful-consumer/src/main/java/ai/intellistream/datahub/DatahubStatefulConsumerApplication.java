// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub;

import ai.intellistream.datahub.config.PulsarVaultSecrets;
import ai.intellistream.datahub.config.MetricsTlsVaultSecrets;
import ai.intellistream.datahub.config.VaultConfigurationLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
		"ai.intellistream.datahub.config",
		"ai.intellistream.datahub.pulsar",
		"ai.intellistream.datahub.timeseries",
		"ai.intellistream.datahub.clickhouse",
		"ai.intellistream.datahub.services",
		"ai.intellistream.datahub.tenant",
		// Weekly event type/sub_type/status/source dimension reconciliation (single-active here).
		"ai.intellistream.datahub.scheduled",
		// EventDimensionRepository, which the reconciliation rebuilds. Scoped to this one package
		// to avoid pulling in the rest of infra's repositories.
		"ai.intellistream.datahub.repositories.event"
})
public class DatahubStatefulConsumerApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(DatahubStatefulConsumerApplication.class);
		app.addListeners(new VaultConfigurationLoader(new PulsarVaultSecrets(),
				new MetricsTlsVaultSecrets(MetricsTlsVaultSecrets.SERVER_SSL)));
		app.run(args);
	}

}
