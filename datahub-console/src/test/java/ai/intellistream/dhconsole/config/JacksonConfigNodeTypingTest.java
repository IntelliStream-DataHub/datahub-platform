// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.config;

import ai.intellistream.datahub.api.responses.DataWrapper;
import ai.intellistream.datahub.models.Asset;
import ai.intellistream.datahub.models.NodeModel;
import ai.intellistream.datahub.models.Resource;
import ai.intellistream.datahub.timeseries.Timeseries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The console's one hook into label-typed node reads.
 *
 * <p>Every typed response from the api reaches the console through the Feign decoder, and that
 * decoder is handed the Boot-built {@code JsonMapper} that {@link JacksonConfig}'s customizer
 * shapes (see {@code DatahubApiConfig}). Delete the customizer and nothing fails to compile: the
 * console still builds, still starts, and then throws at runtime the first time a page asks for a
 * resource, because Jackson cannot instantiate an abstract {@code NodeModel}. That is a poor way
 * to find out, so this pins the customizer's contract directly.
 */
class JacksonConfigNodeTypingTest {

    /** A mapper shaped exactly as the customizer shapes Boot's. */
    private static JsonMapper configured() {
        JsonMapper.Builder builder = JsonMapper.builder();
        for (JsonMapperBuilderCustomizer customizer : List.of(new JacksonConfig().nodeModelSubtypes())) {
            customizer.customize(builder);
        }
        return builder.build();
    }

    private static final String MIXED_PAGE = """
            {"items":[
              {"externalId":"pump_1","name":"Pump 1","labels":["ASSET"],
               "geoLocation":{"type":"Point","coordinates":[10.75,59.91]}},
              {"externalId":"flow_1","name":"Flow 1","labels":["TIMESERIES"],"unit":"kg/hr"},
              {"externalId":"pipe_1","name":"Pipe 1","labels":["PIPE"]}
            ]}""";

    @Test
    @DisplayName("a mixed page of nodes binds to its concrete types")
    void mixedNodesBindToTheirConcreteTypes() {
        DataWrapper<NodeModel> page = configured().readValue(
                MIXED_PAGE, new TypeReference<DataWrapper<NodeModel>>() {});

        assertThat(page.getItems()).hasSize(3);
        assertThat(page.getItems()).element(0).isInstanceOf(Asset.class);
        assertThat(page.getItems()).element(1).isInstanceOf(Timeseries.class);
        // No type-label: a plain resource, not a failure.
        assertThat(page.getItems()).element(2).isInstanceOf(Resource.class);
    }

    /** The per-type fields have to survive, or typing them bought nothing. */
    @Test
    @DisplayName("the fields only the concrete type has come through")
    void concreteFieldsSurvive() {
        DataWrapper<NodeModel> page = configured().readValue(
                MIXED_PAGE, new TypeReference<DataWrapper<NodeModel>>() {});
        List<NodeModel> items = List.copyOf(page.getItems());

        assertThat(((Asset) items.get(0)).getGeoLocation()).isNotNull();
        assertThat(((Timeseries) items.get(1)).getUnit()).isEqualTo("kg/hr");
    }

    /**
     * Without the customizer the same payload fails. This is what the console would do on the
     * first typed read if the bean were dropped, and it is the reason this test exists.
     */
    @Test
    @DisplayName("an unconfigured mapper cannot bind the abstract base")
    void withoutTheCustomizerItFails() {
        assertThatThrownBy(() -> JsonMapper.builder().build()
                .readValue(MIXED_PAGE, new TypeReference<DataWrapper<NodeModel>>() {}))
                .as("if this ever stops throwing, the base is no longer abstract and this "
                        + "test's premise needs revisiting")
                .isInstanceOf(Exception.class);
    }
}
