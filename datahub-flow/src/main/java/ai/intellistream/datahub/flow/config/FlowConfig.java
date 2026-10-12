// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.config;

import ai.intellistream.datahub.flow.api.Processor;
import ai.intellistream.datahub.flow.definition.FlowValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** The validator, over every {@link Processor} bean in the service: what a definition may use. */
@Configuration
public class FlowConfig {

    @Bean
    FlowValidator flowValidator(List<Processor> processors) {
        return new FlowValidator(processors, FlowValidator.Limits.DEFAULTS);
    }
}
