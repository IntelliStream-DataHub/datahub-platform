// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.asset.NetworkSink;
import ai.intellistream.datahub.jpa.domains.Label;
import ai.intellistream.datahub.models.EdgeProxy;
import ai.intellistream.datahub.models.NodeModel;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.util.Set;

/**
 * Sends a fetch-related network to the browser as JSON piece by piece, as each piece comes out of
 * Neo4j, instead of first collecting the whole network in memory and then converting it. A network
 * of any size then uses only a little memory.
 *
 * <p>The JSON looks as it always did: {@code {"edges": [...], "nodes": [...], "labels": [...]}}. The
 * edges come first because they arrive first (see {@link NetworkSink}). Each edge and resource is
 * converted by the application's own JSON mapper, so it looks exactly as it did when Spring
 * converted the whole network at once.
 *
 * <p>The output is not flushed after every piece, which would mean one network write per resource:
 * the server buffers it and sends it in chunks. The server also closes the output stream.
 */
final class NetworkJsonWriter implements NetworkSink {

    private final ObjectWriter writer;
    private final JsonGenerator generator;
    private boolean nodes;

    NetworkJsonWriter(JsonMapper mapper, OutputStream out) {
        this.writer = mapper.writer()
                .without(SerializationFeature.FLUSH_AFTER_WRITE_VALUE)
                .without(StreamWriteFeature.AUTO_CLOSE_TARGET);
        this.generator = writer.createGenerator(out);
        generator.writeStartObject();
        generator.writeName("edges");
        generator.writeStartArray();
    }

    @Override
    public void edge(EdgeProxy edge) {
        writer.writeValue(generator, edge);
    }

    @Override
    public void node(NodeModel node) {
        startNodes();
        writer.writeValue(generator, node);
    }

    /** Writes the label list and closes the JSON document. Nothing may be written after this. */
    void finish(Set<Label> labels) {
        startNodes();
        generator.writeEndArray();
        generator.writeName("labels");
        writer.writeValue(generator, labels);
        generator.writeEndObject();
        generator.close();
    }

    private void startNodes() {
        if (!nodes) {
            generator.writeEndArray();
            generator.writeName("nodes");
            generator.writeStartArray();
            nodes = true;
        }
    }
}
