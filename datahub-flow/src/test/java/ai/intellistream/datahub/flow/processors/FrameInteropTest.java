// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.processors;

import ai.intellistream.datahub.api.binary.DatapointFrame;
import ai.intellistream.datahub.api.binary.DatapointFrameWriter;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.TimeStampMilliTZVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A datapoint frame's payload, decompressed, is a standard Arrow IPC stream that arrow-vector reads:
 * the platform's hand-written Arrow (api-model) and arrow-java agree. The source does not need this
 * — it reads the frames the SDK has already decoded — but a processor working on records content,
 * or a flow passing frames on through Pulsar, will.
 */
class FrameInteropTest {

    @Test
    void arrowVectorReadsADecompressedFramePayload() throws Exception {
        ZstdPayloadCodec codec = new ZstdPayloadCodec();
        DatapointFrameWriter writer = DatapointFrameWriter.forType(DatapointValueType.FLOAT)
                .series(42, "ts1");
        writer.addFloat(42, 1_000, 1.5);
        writer.addFloat(42, 2_000, -2.25);
        DatapointFrame frame = DatapointFrame.parseEnvelopes(writer.build(codec)).getFirst();

        byte[] raw = codec.decompress(frame.payloadBytes(), frame.rawLength());
        try (RootAllocator allocator = new RootAllocator();
             ArrowStreamReader reader = new ArrowStreamReader(new ByteArrayInputStream(raw), allocator)) {
            assertThat(reader.loadNextBatch()).isTrue();
            VectorSchemaRoot root = reader.getVectorSchemaRoot();
            assertThat(root.getSchema().getFields()).extracting(f -> f.getName())
                    .containsExactly("timeseries_id", "timestamp", "value");
            assertThat(root.getRowCount()).isEqualTo(2);
            assertThat(((BigIntVector) root.getVector("timeseries_id")).get(1)).isEqualTo(42);
            assertThat(((TimeStampMilliTZVector) root.getVector("timestamp")).get(1)).isEqualTo(2_000);
            assertThat(((Float8Vector) root.getVector("value")).get(1)).isEqualTo(-2.25);
            assertThat(reader.loadNextBatch()).isFalse();
        }
    }
}
