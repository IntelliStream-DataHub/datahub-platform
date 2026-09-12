// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.rvm;

import ai.intellistream.datahub.config.MetricsTlsVaultSecrets;
import ai.intellistream.datahub.config.VaultConfigurationLoader;
import ai.intellistream.datahub.rvm.config.RvmVaultSecrets;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Converts AVEVA PDMS/E3D models to glTF, so the console's viewer can open them.
 *
 * <p>Stateless and synchronous: a request names a file, the service fetches it from the api with the
 * caller's own JWT, runs the converter binary and returns the model. Nothing is queued and nothing
 * is stored, because the conversion costs milliseconds; see {@code tools/rvm-converter/README.md}
 * for the measurement that decided it.
 */
@SpringBootApplication
public class DatahubRvmConverterApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(DatahubRvmConverterApplication.class);
        // Registered here (not in spring.factories) so it runs on real startup but not in tests.
        app.addListeners(new VaultConfigurationLoader(new RvmVaultSecrets(),
                new MetricsTlsVaultSecrets(MetricsTlsVaultSecrets.MANAGEMENT_SSL)));
        app.run(args);
    }
}
