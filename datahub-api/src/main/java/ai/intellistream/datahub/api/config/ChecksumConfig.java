// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.config;

import ai.intellistream.datahub.helpers.checksum.ChecksumFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the (Spring-free) {@link ChecksumFactory} from commons as a bean, built from the
 * {@code datahub.files.checksum.algorithm} property.
 */
@Configuration
@Slf4j
public class ChecksumConfig {

    @Bean
    public ChecksumFactory checksumFactory(ChecksumProperties properties) {
        ChecksumFactory factory = new ChecksumFactory(properties.getAlgorithm());
        // Build one instance up front so an unavailable algorithm fails at startup, not on the
        // first upload.
        factory.create();
        log.info("File checksum algorithm: {}", properties.getAlgorithm());
        return factory;
    }
}
