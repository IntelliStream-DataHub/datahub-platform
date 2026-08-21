// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.api.responses.DataWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The converter's boundary: object graphs are ours, finished bytes are not.
 *
 * <p>A {@code String} or {@code byte[]} body is JSON text already. Serialized again it comes back
 * quoted and escaped (or base64), which is JSON wrapped in JSON — the shape that made {@code /mcp}
 * unreadable to every client and {@code /api-docs} come out as a base64 blob. Spring AI's MCP
 * transport writes its JSON-RPC envelope exactly that way:
 * {@code ServerResponse.ok().contentType(APPLICATION_JSON).body(envelopeAsString)}.
 *
 * <p>The dedicated String/byte[] converters claim these too, so without the guard the outcome
 * rests on converter ordering — which this class does not control. See the read-side twin in
 * {@link StrictJacksonJsonHttpMessageConverter#canRead}.
 */
class StrictJacksonConverterBoundaryTest {

    private final StrictJacksonJsonHttpMessageConverter converter =
            new StrictJacksonJsonHttpMessageConverter(JsonMapper.builder().build());

    @Test
    void declinesAStringBodyThatIsAlreadyJson() {
        assertThat(converter.canWrite(String.class, MediaType.APPLICATION_JSON)).isFalse();
        assertThat(converter.canWrite(ResolvableType.forClass(String.class), String.class,
                MediaType.APPLICATION_JSON)).isFalse();
    }

    @Test
    void declinesAByteArrayBody() {
        assertThat(converter.canWrite(byte[].class, MediaType.APPLICATION_JSON)).isFalse();
    }

    @Test
    void stillWritesTheObjectGraphsItIsFor() {
        assertThat(converter.canWrite(DataWrapper.class, MediaType.APPLICATION_JSON)).isTrue();
    }

    @Test
    void theStockConverterWouldHaveClaimedBoth() {
        // Why the override is needed at all: the parent says yes to exactly the two cases above,
        // so ordering was the only thing keeping the raw bodies intact.
        var stock = new JacksonJsonHttpMessageConverter(JsonMapper.builder().build());
        assertThat(stock.canWrite(String.class, MediaType.APPLICATION_JSON)).isTrue();
        assertThat(stock.canWrite(byte[].class, MediaType.APPLICATION_JSON)).isTrue();
    }
}
