// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.rvm.config;

import ai.intellistream.datahub.rvm.RvmConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Where the converter binary lives and how long a conversion may take.
 *
 * <p>The binary is built by {@code tools/rvm-converter/build.sh} and shipped alongside this service;
 * it is statically linked, so the image needs nothing else to run it.
 */
@Configuration
public class ConverterProperties {

    @Bean
    RvmConverter rvmConverter(
            @Value("${rvm.converter.binary:/opt/datahub/bin/rvm-converter}") Path binary,
            @Value("${rvm.converter.timeout:PT5M}") Duration timeout) {
        return new RvmConverter(binary, timeout);
    }
}
