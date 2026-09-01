// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.NodeModelSubtypes;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
public class JacksonConfig {

    @Bean
    @Order(1) // Runs after Boot's default customizer (which is 0)
    public JsonMapperBuilderCustomizer customNullHandling() {
        return builder -> {
            // Label-keyed polymorphic reads for the node family (the type-label is the
            // discriminator; see NodeModelSubtypes).
            builder.addModule(new NodeModelSubtypes());
            // Map and Collection null-to-empty logic
            builder.withConfigOverride(java.util.Map.class, override ->
                    override.setNullHandling(JsonSetter.Value.forValueNulls(Nulls.AS_EMPTY)));
            builder.withConfigOverride(java.util.Collection.class, override ->
                    override.setNullHandling(JsonSetter.Value.forValueNulls(Nulls.AS_EMPTY)));
            // primitive ints get set to class default value if input is None.
            builder.withConfigOverride(int.class, override ->
                    override.setNullHandling(JsonSetter.Value.forValueNulls(Nulls.SKIP)));
        };
    }
}