// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.helpers;

import feign.RequestTemplate;
import feign.codec.EncodeException;
import feign.codec.Encoder;
import java.lang.reflect.Type;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.exc.JacksonIOException;

public class Jackson3Encoder implements Encoder {
    private final JsonMapper mapper;

    public Jackson3Encoder(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void encode(Object object, Type bodyType, RequestTemplate template) throws EncodeException {
        try {
            template.body(mapper.writeValueAsString(object));
        } catch (JacksonIOException e) {
            throw new EncodeException(e.getMessage(), e);
        }
    }
}
