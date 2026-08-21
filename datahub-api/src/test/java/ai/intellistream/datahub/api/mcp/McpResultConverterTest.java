// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.mcp;

import ai.intellistream.datahub.models.EdgeProxy;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The universal NON_EMPTY floor applied to every MCP tool result. Uses {@link EdgeProxy},
 * which carries no class-level {@code @JsonInclude}, so any trimming here comes from the
 * converter's mapper — not the DTO — proving the global inclusion is what strips the noise.
 */
class McpResultConverterTest {

    private final McpResultConverter converter = new McpResultConverter();

    @Test
    void stripsNullsAndEmptyCollections() {
        EdgeProxy edge = new EdgeProxy();
        edge.setId(1L);
        edge.setStart(2L);
        edge.setEnd(3L);
        edge.setType("PROCESSED_BY");
        edge.setRelationshipTypeId(4L);
        // description left null, metadata left as an empty map
        edge.setMetadata(new HashMap<>());

        String json = converter.convert(edge, EdgeProxy.class);

        assertThat(json).contains("\"type\":\"PROCESSED_BY\"");
        assertThat(json).doesNotContain("description");
        assertThat(json).doesNotContain("metadata");
    }

    @Test
    void voidReturnsDoneSentinel() {
        assertThat(converter.convert(null, Void.TYPE)).isEqualTo("Done");
    }

    @Test
    void datesSerializeAsIsoStringsNotEpoch() {
        record Holder(ZonedDateTime at) {}
        Holder h = new Holder(ZonedDateTime.parse("2026-01-02T03:04:05Z"));

        String json = converter.convert(h, Holder.class);

        assertThat(json).contains("2026-01-02T03:04:05");
        assertThat(json).doesNotContain("1767"); // no epoch-millis prefix
    }
}
