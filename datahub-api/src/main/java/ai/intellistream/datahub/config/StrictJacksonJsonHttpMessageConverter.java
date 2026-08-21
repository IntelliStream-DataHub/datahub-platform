// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.api.controllers.errors.UnknownRequestFieldsException;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.lang.Nullable;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The request-body converter, made strict about fields the api does not declare — reporting all of
 * them at once.
 *
 * <p>Jackson's own {@code FAIL_ON_UNKNOWN_PROPERTIES} throws on the first offender, so a caller
 * fixes one field per round trip and the one they hear about depends on JSON ordering. Instead
 * {@link UnknownFieldCollector} claims each unknown field so the parse runs to completion, and this
 * fails afterwards with the full set.
 *
 * <p>The collector is opened and drained around each read in a {@code finally}, so a body that
 * fails for some other reason cannot leave entries behind for the next request on a pooled thread.
 *
 * <h2>Raw-text reads are not ours</h2>
 * See {@link #canRead(ResolvableType, MediaType)}: a caller asking for the body as a
 * {@code String} wants the JSON text, not an object graph, and this converter must decline.
 */
public class StrictJacksonJsonHttpMessageConverter extends JacksonJsonHttpMessageConverter {

    public StrictJacksonJsonHttpMessageConverter(JsonMapper mapper) {
        super(mapper);
    }

    /**
     * Declines raw-text and raw-byte writes, leaving them to {@code StringHttpMessageConverter} and
     * {@code ByteArrayHttpMessageConverter}.
     *
     * <p>The mirror of {@link #canRead}: a body that is already a {@code String} or a {@code byte[]}
     * is finished text, not an object graph to serialize. Handed one, Jackson JSON-encodes it —
     * a String comes out quoted and escaped, a byte[] comes out base64 — so the caller receives
     * JSON wrapped in JSON. Spring AI's MCP transport writes exactly this shape
     * ({@code ServerResponse.ok().contentType(APPLICATION_JSON).body(jsonRpcEnvelopeAsString)}),
     * as does springdoc's {@code /api-docs}.
     *
     * <p>Ordering alone is not the guarantee it looks like: this converter and the dedicated
     * String/byte[] ones all answer {@code true} here, so whichever sits earlier in the list wins.
     * Declining states the boundary outright, the same way {@link #canRead} does.
     */
    @Override
    public boolean canWrite(@Nullable Class<?> clazz, @Nullable MediaType mediaType) {
        if (isRawBody(clazz)) {
            return false;
        }
        return super.canWrite(clazz, mediaType);
    }

    @Override
    public boolean canWrite(ResolvableType type, @Nullable Class<?> clazz, @Nullable MediaType mediaType) {
        if (isRawBody(clazz) || isRawBody(type.resolve())) {
            return false;
        }
        return super.canWrite(type, clazz, mediaType);
    }

    private static boolean isRawBody(@Nullable Class<?> clazz) {
        return clazz != null && (CharSequence.class.isAssignableFrom(clazz) || byte[].class.equals(clazz));
    }

    /**
     * Declines raw-text reads, leaving them to {@code StringHttpMessageConverter}.
     *
     * <p>A {@code String} target has no declared fields, so there is nothing for this converter's
     * unknown-field checking to be about; asked to read one it would hand the JSON object to
     * Jackson as a {@code String} and fail with "cannot deserialize {@code String} from Object
     * value". That is not hypothetical — it is how this class took down the MCP endpoint. Spring
     * AI's {@code WebMvcStatelessServerTransport} reads the body with
     * {@code ServerRequest.body(String.class)} and parses the JSON-RPC envelope itself, so every
     * request to {@code POST /mcp} failed with a 500 for every MCP client, not just the console's.
     *
     * <p>Both this converter and {@code StringHttpMessageConverter} answer {@code true} for
     * {@code String} + {@code application/json}, so which one serves a raw-text read comes down to
     * their order in the converter list. Declining here states the boundary outright instead of
     * resting on that order.
     */
    @Override
    public boolean canRead(ResolvableType type, @Nullable MediaType mediaType) {
        Class<?> raw = type.resolve();
        if (raw != null && CharSequence.class.isAssignableFrom(raw)) {
            return false;
        }
        return super.canRead(type, mediaType);
    }

    @Override
    public Object read(ResolvableType type, HttpInputMessage inputMessage, @Nullable Map<String, Object> hints)
            throws IOException {
        UnknownFieldCollector.begin();
        try {
            Object value = super.read(type, inputMessage, hints);
            failIfUnknownFieldsWereFound();
            return value;
        } finally {
            UnknownFieldCollector.drain();
        }
    }

    @Override
    protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage) throws IOException {
        UnknownFieldCollector.begin();
        try {
            Object value = super.readInternal(clazz, inputMessage);
            failIfUnknownFieldsWereFound();
            return value;
        } finally {
            UnknownFieldCollector.drain();
        }
    }

    private static void failIfUnknownFieldsWereFound() {
        List<UnknownFieldCollector.UnknownField> found = UnknownFieldCollector.drain();
        if (found.isEmpty()) {
            return;
        }
        throw new UnknownRequestFieldsException(found.stream()
                .map(f -> new UnknownRequestFieldsException.UnknownField(f.pointer(), f.allowed()))
                .toList());
    }
}
