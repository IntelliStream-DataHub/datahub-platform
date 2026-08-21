// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.json;

import ai.intellistream.datahub.models.GeoLocation;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Emits a {@link GeoLocation} as its raw GeoJSON object on the REST wire (not a quoted string).
 * The DTO stores the geometry verbatim as a JSON string; here we write it out unescaped so
 * clients see a nested object. Avro (Pulsar) ignores this serializer and reflects the inner
 * {@code json} string directly.
 */
public class GeoLocationSerializer extends ValueSerializer<GeoLocation> {
    @Override
    public void serialize(GeoLocation value, JsonGenerator gen, SerializationContext ctxt) {
        if (value == null || value.getJson() == null) {
            gen.writeNull();
            return;
        }
        gen.writeRawValue(value.getJson());
    }
}
