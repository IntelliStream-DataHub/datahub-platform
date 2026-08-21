// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.jspecify.annotations.NonNull;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Rejects a request body naming a field this api does not have, instead of dropping it.
 *
 * <p>Jackson 3 defaults to ignoring unknown properties, which made every typo and every retired
 * field a silent no-op answered with {@code 200} — the caller is told their change was applied when
 * nothing happened. {@link UnreadableRequestBodyExceptionHandler} turns the resulting failure into
 * a {@code 400} naming the offending field.
 *
 * <h2>Why this is not the {@code spring.jackson} property</h2>
 * {@code spring.jackson.deserialization.fail-on-unknown-properties} configures the shared
 * {@link JsonMapper} bean, and that bean is injected far beyond request handling — most sharply by
 * {@code TenantConfigService}, which parses the Vault tenant registry with it. Setting it globally
 * made one undeclared key anywhere in that registry throw, so the api loaded no tenants and refused
 * to start, while the consumers (which do not read this file) kept running. Strictness is right for
 * a request body we define the contract for, and wrong for a third party's payload, which is free
 * to grow keys we do not know about.
 *
 * <p>So the strict mapper is attached only to the converter that reads {@code @RequestBody}, built
 * by rebuilding the configured one so it keeps every other setting — date handling, modules, naming
 * — and differs in exactly one feature.
 */
@Configuration
@Slf4j
public class StrictRequestBodyConfig implements WebMvcConfigurer {

    private final JsonMapper strictMapper;

    public StrictRequestBodyConfig(JsonMapper configuredMapper) {
        // Not FAIL_ON_UNKNOWN_PROPERTIES: that throws on the first offender, so a body with three
        // stale fields costs three round trips. The collector claims each one so the parse finishes,
        // and the converter fails afterwards with all of them.
        this.strictMapper = configuredMapper.rebuild()
                .addHandler(new UnknownFieldCollector())
                .build();
    }

    /**
     * Extend rather than configure: the default converter list is already built, so replacing the
     * Jackson one in place leaves ordering and every other converter untouched.
     */
    @Override
    public void extendMessageConverters(@NonNull List<HttpMessageConverter<?>> converters) {
        int replaced = 0;
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof JacksonJsonHttpMessageConverter) {
                converters.set(i, new StrictJacksonJsonHttpMessageConverter(strictMapper));
                replaced++;
            }
        }
        if (replaced == 0) {
            // Not fatal — the api still serves, just leniently — but it silently un-does the whole
            // point of this class, so it must not pass unnoticed.
            log.warn("No JacksonJsonHttpMessageConverter found; request bodies will keep ignoring "
                    + "unknown fields. Has the Spring JSON converter been renamed or replaced?");
        } else {
            log.debug("Request bodies reject unknown fields ({} Jackson converter(s) replaced).", replaced);
        }
        warnIfJsonOutranksTheRawBodyConverters(converters);
    }

    /**
     * A JSON converter ahead of the String/byte[] ones silently double-encodes raw bodies.
     *
     * <p>Spring AI's MCP transport hands the finished JSON-RPC envelope to
     * {@code ServerResponse.body(String)} with content type {@code application/json}, and springdoc
     * serves {@code /api-docs} as a {@code byte[]}. Both are written by whichever converter claims
     * them first: the dedicated one writes the bytes through, a JSON converter re-serializes them
     * into a quoted, escaped string (or base64). The order is not ours to set here, so if it ever
     * comes out wrong, say so at startup rather than leaving it to be found through a client that
     * cannot parse the response.
     */
    private static void warnIfJsonOutranksTheRawBodyConverters(List<HttpMessageConverter<?>> converters) {
        int json = -1, raw = -1;
        for (int i = 0; i < converters.size(); i++) {
            HttpMessageConverter<?> c = converters.get(i);
            if (json < 0 && c instanceof JacksonJsonHttpMessageConverter) {
                json = i;
            }
            if (raw < 0 && (c instanceof StringHttpMessageConverter || c instanceof ByteArrayHttpMessageConverter)) {
                raw = i;
            }
        }
        if (json >= 0 && raw >= 0 && json < raw) {
            log.warn("A JSON message converter ({}) sits ahead of the String/byte[] converters at "
                            + "index {} vs {}; raw JSON bodies (the /mcp JSON-RPC envelope, /api-docs) "
                            + "will be serialized twice. Converter order: {}",
                    converters.get(json).getClass().getSimpleName(), json, raw, describe(converters));
        } else {
            log.debug("Message converter order: {}", describe(converters));
        }
    }

    private static String describe(List<HttpMessageConverter<?>> converters) {
        return converters.stream().map(c -> c.getClass().getSimpleName()).collect(Collectors.joining(", "));
    }
}
