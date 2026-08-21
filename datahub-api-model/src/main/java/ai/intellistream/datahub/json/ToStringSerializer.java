// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.json;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

public class ToStringSerializer extends ValueSerializer<Long> {
    @Override
    public void serialize(Long value, JsonGenerator gen, SerializationContext ctxt) {
        gen.writeString(value.toString());
    }
}
