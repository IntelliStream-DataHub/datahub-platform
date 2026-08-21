// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.helpers;

import feign.Response;
import feign.Util;
import feign.codec.Decoder;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;

public class Jackson3Decoder implements Decoder {

    private final JsonMapper mapper;

    public Jackson3Decoder(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Object decode(Response response, Type type) throws IOException {
        if (response.body() == null) return null;
        Reader reader = response.body().asReader(Util.UTF_8);
        if (!reader.markSupported()) {
            reader = new BufferedReader(reader, 1);
        }
        try {
            // Read the first byte to see if it's empty
            reader.mark(1);
            if (reader.read() == -1) {
                return null; // Eagerly returning null if there is no body
            }
            reader.reset();
            return mapper.readValue(reader, mapper.constructType(type));
        } catch (Exception e) {
            if (e.getCause() != null && e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw new IOException(e);
        }
    }
}
