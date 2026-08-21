// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.analysis;

import ai.intellistream.datahub.analysis.config.VaultConfigurationLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The timeseries relationship-analysis service. A lean, stateless web app: the console posts an
 * {@code AnalysisForm} to {@code POST /analysis}, and it gathers the ACL'd series + graph it needs
 * from the api via the Java SDK (forwarding the caller's JWT), then runs the analysis in-process. It
 * has no database and no notion of tenants/datasets — the api enforces those on the data it serves
 * back. Its one trust boundary is OAuth2: it validates the user's own JWT (see
 * {@code config.SecurityConfig}) against the issuer it loads from Vault at startup.
 */
@SpringBootApplication
public class DatahubAnalysisApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(DatahubAnalysisApplication.class);
		// Registered here (not in spring.factories) so it runs on real startup but not in tests.
		app.addListeners(new VaultConfigurationLoader());
		app.run(args);
	}
}
