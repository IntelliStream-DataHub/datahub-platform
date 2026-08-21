// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole;

import ai.intellistream.dhconsole.config.VaultConfigurationLoader;
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
		SpringApplication app = new SpringApplication(DatahubConsoleApplication.class);
		app.addListeners(new VaultConfigurationLoader());
		app.run(args);
	}

	@Bean
	public RestTemplate restTemplate() {
		return new RestTemplate();
	}

}
