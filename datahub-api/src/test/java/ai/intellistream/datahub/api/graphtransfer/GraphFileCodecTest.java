package ai.intellistream.datahub.api.graphtransfer;

import ai.intellistream.datahub.api.graphtransfer.GraphExportFile.ExportedNode;
import ai.intellistream.datahub.api.graphtransfer.GraphExportFile.ExportedRelation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphFileCodecTest {

    private static GraphExportFile sampleFile() {
        var root = new ExportedNode(
                "plant_root", "Plant root", "The root", "sap", true,
                "{\"type\":\"Point\",\"coordinates\":[5.3,60.4]}", null,
                List.of("ASSET", "PLANT"), Map.of("site", "bergen"));
        var pipe = new ExportedNode(
                "pipe_a1", "Pipe A1", null, null, false, null, "ds_main",
                List.of("PIPE"), Map.of());
        var relation = new ExportedRelation(
                "plant_root", "pipe_a1", "HAS_PART", "root to pipe", "ds_main", Map.of("k", "v"));
        return new GraphExportFile(List.of(root, pipe), List.of(relation));
    }

    @Test
    void roundTripPreservesEverything() throws IOException {
        var out = new ByteArrayOutputStream();
        GraphFileCodec.encode(sampleFile(), out);

        GraphExportFile decoded = GraphFileCodec.decode(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(sampleFile(), decoded);
    }

    @Test
    void outputIsGzip() throws IOException {
        var out = new ByteArrayOutputStream();
        GraphFileCodec.encode(sampleFile(), out);
        byte[] bytes = out.toByteArray();

        assertTrue(bytes.length > 2);
        assertArrayEquals(new byte[]{ (byte) 0x1f, (byte) 0x8b }, new byte[]{ bytes[0], bytes[1] });
    }

    @Test
    void roundTripKeepsNullsAndUnicode() throws IOException {
        var node = new ExportedNode(
                "unicode_æøå", "Blåbærsyltetøy 🫐", null, null, false, null, null,
                List.of("ASSET"), Map.of("nøkkel", "verdi med mellomrom"));
        var file = new GraphExportFile(List.of(node), List.of());

        var out = new ByteArrayOutputStream();
        GraphFileCodec.encode(file, out);
        GraphExportFile decoded = GraphFileCodec.decode(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(file, decoded);
    }

    @Test
    void rejectsNonGzipInput() {
        var in = new ByteArrayInputStream("not a graph file at all".getBytes());
        assertThrows(InvalidGraphFileException.class, () -> GraphFileCodec.decode(in));
    }

    @Test
    void rejectsGzipWithWrongMagic() throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(out)) {
            gzip.write("XXXX-something-else".getBytes());
        }
        var in = new ByteArrayInputStream(out.toByteArray());
        assertThrows(InvalidGraphFileException.class, () -> GraphFileCodec.decode(in));
    }

    @Test
    void rejectsUnsupportedVersion() throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(out)) {
            gzip.write(GraphFileCodec.MAGIC);
            gzip.write(99);
        }
        var in = new ByteArrayInputStream(out.toByteArray());
        var e = assertThrows(InvalidGraphFileException.class, () -> GraphFileCodec.decode(in));
        assertTrue(e.getMessage().contains("version"));
    }

    @Test
    void rejectsTruncatedFile() throws IOException {
        var out = new ByteArrayOutputStream();
        GraphFileCodec.encode(sampleFile(), out);
        byte[] full = out.toByteArray();
        byte[] truncated = new byte[full.length / 2];
        System.arraycopy(full, 0, truncated, 0, truncated.length);

        var in = new ByteArrayInputStream(truncated);
        assertThrows(InvalidGraphFileException.class, () -> GraphFileCodec.decode(in));
    }
}
