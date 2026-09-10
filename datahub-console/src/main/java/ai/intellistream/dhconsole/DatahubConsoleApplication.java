// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@ComponentScan(basePackages = {
		"ai.intellistream.datahub.config",
		"ai.intellistream.datahub.tenant",
		"ai.intellistream.dhconsole.security",
		"ai.intellistream.dhconsole.controllers",
		"ai.intellistream.dhconsole.config",
		"ai.intellistream.dhconsole.chat",
		"ai.intellistream.dhconsole.services",
		"ai.intellistream.dhconsole.util",
		"ai.intellistream.dhconsole.i18n",
		"ai.intellistream.datahub.helpers.utils",
})
@Configuration
public class DatahubConsoleApplication {

	public static void main(String[] args) {
		// The Vault loader is registered through META-INF/spring.factories (see
		// dhconsole.config.VaultConfigurationLoader for why), so it is not added here as well.
		SpringApplication.run(DatahubConsoleApplication.class, args);
	}

	@Bean
	public RestTemplate restTemplate() {
		return new RestTemplate();
	}

}
