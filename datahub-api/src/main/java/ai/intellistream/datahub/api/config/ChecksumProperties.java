// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.config;

import ai.intellistream.datahub.helpers.checksum.ChecksumAlgorithm;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code datahub.files.checksum.*}. The configured algorithm is applied to every file
 * uploaded through {@code PUT /files}. Supported values: {@code SHA-256} (default), {@code SHA-1},
 * {@code SHA-512}, {@code BLAKE3}.
 */
@Component
@ConfigurationProperties(prefix = "datahub.files.checksum")
public class ChecksumProperties {

    private ChecksumAlgorithm algorithm = ChecksumAlgorithm.SHA_256;

    public ChecksumAlgorithm getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(ChecksumAlgorithm algorithm) {
        this.algorithm = algorithm;
    }
}
