// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.api.services.ReqLogService;
import org.apache.pulsar.client.api.Producer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableAsync;
import tools.jackson.databind.json.JsonMapper;

@EnableAsync
public class RequestLogConfig {

    @Bean
    public ReqLogService reqLogService(
            JsonMapper jsonMapper,
            Producer<String> httpMessageProducer,
            Environment environment
    ) {
        return new ReqLogService(jsonMapper, httpMessageProducer, environment);
    }
}
