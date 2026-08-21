// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.json;

import ai.intellistream.datahub.models.GeoLocation;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads an incoming GeoJSON object subtree and stores it verbatim on {@link GeoLocation} as a
 * JSON string. Structural GeoJSON validity is enforced separately by bean validation
 * ({@code GeoLocation#isValidGeoJson}); this only captures the raw shape.
 */
public class GeoLocationDeserializer extends ValueDeserializer<GeoLocation> {
    @Override
    public GeoLocation deserialize(JsonParser parser, DeserializationContext ctxt) {
        JsonNode node = ctxt.readTree(parser);
        if (node == null || node.isNull()) {
            return null;
        }
        return new GeoLocation(node.toString());
    }
}
