package ai.intellistream.datahub.api.graphtransfer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

/**
 * Binary wire format for {@link GraphExportFile}. The whole file is one gzip stream; inside it, a
 * big-endian {@link DataOutputStream} layout:
 *
 * <pre>
 *   magic   "DHGX" (4 bytes)
 *   version byte (currently 1)
 *   int nodeCount, then per node:
 *     str externalId, str name, str description, str source,
 *     bool isRoot, str geoJson, str dataSetExternalId,
 *     int labelCount + str[], int metadataCount + (str,str)[]
 *   int relationCount, then per relation:
 *     str fromExternalId, str toExternalId, str type, str description,
 *     str dataSetExternalId, int metadataCount + (str,str)[]
 * </pre>
 *
 * where {@code str} is an int byte length (-1 for null) followed by that many UTF-8 bytes —
 * length-prefixed rather than {@code writeUTF} so values are not capped at 64KB.
 */
public final class GraphFileCodec {

    static final byte[] MAGIC = { 'D', 'H', 'G', 'X' };
    static final byte VERSION = 1;

    /** Upper bound on any single length/count field, so a corrupt file fails fast instead of OOMing. */
    private static final int MAX_LENGTH = 64 * 1024 * 1024;

    private GraphFileCodec() {
    }

    public static void encode(GraphExportFile file, OutputStream out) throws IOException {
        try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(out))) {
            data.write(MAGIC);
            data.writeByte(VERSION);

            data.writeInt(file.nodes().size());
            for (GraphExportFile.ExportedNode node : file.nodes()) {
                writeString(data, node.externalId());
                writeString(data, node.name());
                writeString(data, node.description());
                writeString(data, node.source());
                data.writeBoolean(node.isRoot());
                writeString(data, node.geoJson());
                writeString(data, node.dataSetExternalId());
                data.writeInt(node.labels().size());
                for (String label : node.labels()) {
                    writeString(data, label);
                }
                writeMap(data, node.metadata());
            }

            data.writeInt(file.relations().size());
            for (GraphExportFile.ExportedRelation relation : file.relations()) {
                writeString(data, relation.fromExternalId());
                writeString(data, relation.toExternalId());
                writeString(data, relation.type());
                writeString(data, relation.description());
                writeString(data, relation.dataSetExternalId());
                writeMap(data, relation.metadata());
            }
        }
    }

    public static GraphExportFile decode(InputStream in) {
        try (DataInputStream data = new DataInputStream(new GZIPInputStream(in))) {
            byte[] magic = new byte[MAGIC.length];
            data.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new InvalidGraphFileException("Not a DataHub graph export file.");
            }
            byte version = data.readByte();
            if (version != VERSION) {
                throw new InvalidGraphFileException(
                        "Unsupported graph export version " + version + " (supported: " + VERSION + ").");
            }

            int nodeCount = readCount(data, "node count");
            List<GraphExportFile.ExportedNode> nodes = new ArrayList<>(Math.min(nodeCount, 10_000));
            for (int i = 0; i < nodeCount; i++) {
                String externalId = readString(data);
                String name = readString(data);
                String description = readString(data);
                String source = readString(data);
                boolean isRoot = data.readBoolean();
                String geoJson = readString(data);
                String dataSetExternalId = readString(data);
                int labelCount = readCount(data, "label count");
                List<String> labels = new ArrayList<>(Math.min(labelCount, 100));
                for (int l = 0; l < labelCount; l++) {
                    labels.add(readString(data));
                }
                Map<String, String> metadata = readMap(data);
                nodes.add(new GraphExportFile.ExportedNode(
                        externalId, name, description, source, isRoot, geoJson, dataSetExternalId,
                        labels, metadata));
            }

            int relationCount = readCount(data, "relation count");
            List<GraphExportFile.ExportedRelation> relations = new ArrayList<>(Math.min(relationCount, 10_000));
            for (int i = 0; i < relationCount; i++) {
                relations.add(new GraphExportFile.ExportedRelation(
                        readString(data), readString(data), readString(data), readString(data),
                        readString(data), readMap(data)));
            }
            return new GraphExportFile(nodes, relations);
        } catch (ZipException | EOFException e) {
            throw new InvalidGraphFileException("Not a DataHub graph export file.", e);
        } catch (IOException e) {
            throw new InvalidGraphFileException("Could not read the graph export file.", e);
        }
    }

    private static void writeString(DataOutputStream data, String value) throws IOException {
        if (value == null) {
            data.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        data.writeInt(bytes.length);
        data.write(bytes);
    }

    private static String readString(DataInputStream data) throws IOException {
        int length = data.readInt();
        if (length == -1) {
            return null;
        }
        if (length < 0 || length > MAX_LENGTH) {
            throw new InvalidGraphFileException("Corrupt graph export file: impossible string length " + length + ".");
        }
        byte[] bytes = new byte[length];
        data.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeMap(DataOutputStream data, Map<String, String> map) throws IOException {
        Map<String, String> safe = (map == null) ? Map.of() : map;
        data.writeInt(safe.size());
        for (Map.Entry<String, String> entry : safe.entrySet()) {
            writeString(data, entry.getKey());
            writeString(data, entry.getValue());
        }
    }

    private static Map<String, String> readMap(DataInputStream data) throws IOException {
        int size = readCount(data, "metadata count");
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            map.put(readString(data), readString(data));
        }
        return map;
    }

    private static int readCount(DataInputStream data, String what) throws IOException {
        int count = data.readInt();
        if (count < 0 || count > MAX_LENGTH) {
            throw new InvalidGraphFileException("Corrupt graph export file: impossible " + what + " " + count + ".");
        }
        return count;
    }
}
