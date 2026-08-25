// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.config;

import ai.intellistream.datahub.models.NodeModelSubtypes;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    /**
     * Label-keyed polymorphic reads for the node family. The customizer shapes the Boot-built
     * {@code JsonMapper}, which is also the mapper the Feign decoder gets (see
     * {@code DatahubApiConfig}), so api responses typed as {@code NodeModel} dispatch on the
     * type-label here exactly as they do in the api and the Java SDK.
     */
    @Bean
    public JsonMapperBuilderCustomizer nodeModelSubtypes() {
        return builder -> builder.addModule(new NodeModelSubtypes());
    }
}
