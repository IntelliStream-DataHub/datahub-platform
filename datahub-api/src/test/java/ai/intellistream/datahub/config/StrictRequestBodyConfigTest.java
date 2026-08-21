// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.tenant.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Strictness must reach request bodies and nothing else.
 *
 * <p>The first attempt at this used {@code spring.jackson.deserialization.fail-on-unknown-properties},
 * which configures the shared mapper. {@code TenantConfigService} injects that same bean to read the
 * Vault tenant registry, so one undeclared key there meant the api loaded no tenants and refused to
 * start — a third party's payload judged against our own contract. These tests pin both halves.
 */
class StrictRequestBodyConfigTest {

    /** A registry entry with a key the Tenant model does not declare, as Vault may well grow. */
    private static final String TENANT_JSON = """
            {"dev-org-1":{"org-id":"251eb4fe","postgresql":{"uri":"jdbc:postgresql://h/db","user":"u"},
                          "some-newer-key":"whatever"}}""";

    private static List<HttpMessageConverter<?>> defaultishConverters() {
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        converters.add(new ByteArrayHttpMessageConverter());
        converters.add(new StringHttpMessageConverter());
        converters.add(new JacksonJsonHttpMessageConverter());
        return converters;
    }

    @Test
    void theSharedMapperStaysLenientSoVaultPayloadsStillParse() {
        JsonMapper shared = JsonMapper.builder().build();
        new StrictRequestBodyConfig(shared);   // must not mutate what it was handed

        assertThatCode(() -> shared.readValue(TENANT_JSON, new TypeReference<Map<String, Tenant>>() {}))
                .as("the injected bean is what reads the Vault tenant registry")
                .doesNotThrowAnyException();
    }

    @Test
    void theRequestBodyConverterIsTheStrictOne() {
        JsonMapper shared = JsonMapper.builder().build();
        List<HttpMessageConverter<?>> converters = defaultishConverters();

        new StrictRequestBodyConfig(shared).extendMessageConverters(converters);

        assertThat(converters).anyMatch(StrictJacksonJsonHttpMessageConverter.class::isInstance);
    }

    /** Replacing the Jackson converter must not disturb the others or their order. */
    @Test
    void leavesTheOtherConvertersAlone() {
        List<HttpMessageConverter<?>> converters = defaultishConverters();

        new StrictRequestBodyConfig(JsonMapper.builder().build()).extendMessageConverters(converters);

        assertThat(converters).hasSize(3);
        assertThat(converters.get(0)).isInstanceOf(ByteArrayHttpMessageConverter.class);
        assertThat(converters.get(1)).isInstanceOf(StringHttpMessageConverter.class);
        assertThat(converters.get(2)).isInstanceOf(StrictJacksonJsonHttpMessageConverter.class);
    }

    /** No Jackson converter is a silent loss of the whole feature, so it must not throw either. */
    @Test
    void toleratesAConverterListWithoutJackson() {
        List<HttpMessageConverter<?>> converters = new ArrayList<>(List.of(new StringHttpMessageConverter()));

        assertThatCode(() -> new StrictRequestBodyConfig(JsonMapper.builder().build())
                .extendMessageConverters(converters)).doesNotThrowAnyException();
        assertThat(converters).hasSize(1);
    }
}
