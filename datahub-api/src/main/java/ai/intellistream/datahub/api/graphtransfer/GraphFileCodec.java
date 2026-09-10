package ai.intellistream.datahub.api.graphtransfer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
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

    /** Transfer limits: the most nodes / relations one file may carry, in either direction. */
    public static final int MAX_NODES = 2_000_000;
    public static final int MAX_RELATIONS = 2_000_000;

    /** Byte cap on an uploaded file (compressed). ~8x a full 2M/2M export (~61 MB measured). */
    public static final long MAX_COMPRESSED_BYTES = 512L * 1024 * 1024;

    /** Byte cap on what the gzip stream may inflate to, so a gzip bomb fails fast (~625 MB measured
     *  for a full 2M/2M export). */
    static final long MAX_DECOMPRESSED_BYTES = 4L * 1024 * 1024 * 1024;

    /** Upper bound on any single length/count field, so a corrupt file fails fast instead of OOMing. */
    private static final int MAX_LENGTH = 64 * 1024 * 1024;

    private GraphFileCodec() {
    }

    public static void encode(GraphExportFile file, OutputStream out) throws IOException {
        try (GraphFileWriter writer = writer(out, file.nodes().size(), file.relations().size())) {
            for (GraphExportFile.ExportedNode node : file.nodes()) {
                writer.write(node);
            }
            for (GraphExportFile.ExportedRelation relation : file.relations()) {
                writer.write(relation);
            }
        }
    }

    /**
     * Opens a streaming writer: the header goes out immediately, then each item is encoded and
     * gzipped straight to {@code out} as it is written — the file is never buffered whole. Counts
     * are declared up front (the format is count-prefixed); {@link GraphFileWriter#close()} fails
     * if what was written does not match, so a half-written file cannot pass for a complete one.
     */
    public static GraphFileWriter writer(OutputStream out, int nodeCount, int relationCount) throws IOException {
        return new GraphFileWriter(out, nodeCount, relationCount);
    }

    public static final class GraphFileWriter implements java.io.Closeable {

        private final DataOutputStream data;
        private final int nodeCount;
        private final int relationCount;
        private int nodesWritten;
        private int relationsWritten;
        private boolean relationCountWritten;

        private GraphFileWriter(OutputStream out, int nodeCount, int relationCount) throws IOException {
            this.nodeCount = nodeCount;
            this.relationCount = relationCount;
            this.data = new DataOutputStream(new GZIPOutputStream(out));
            data.write(MAGIC);
            data.writeByte(VERSION);
            data.writeInt(nodeCount);
        }

        public void write(GraphExportFile.ExportedNode node) throws IOException {
            if (relationCountWritten) {
                throw new IllegalStateException("Write every node before the first relation.");
            }
            if (nodesWritten >= nodeCount) {
                throw new IllegalStateException("More nodes written than the declared " + nodeCount + ".");
            }
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
            nodesWritten++;
        }

        public void write(GraphExportFile.ExportedRelation relation) throws IOException {
            if (!relationCountWritten) {
                if (nodesWritten != nodeCount) {
                    throw new IllegalStateException(
                            "Wrote " + nodesWritten + " of the declared " + nodeCount + " nodes.");
                }
                data.writeInt(relationCount);
                relationCountWritten = true;
            }
            if (relationsWritten >= relationCount) {
                throw new IllegalStateException("More relations written than the declared " + relationCount + ".");
            }
            writeString(data, relation.fromExternalId());
            writeString(data, relation.toExternalId());
            writeString(data, relation.type());
            writeString(data, relation.description());
            writeString(data, relation.dataSetExternalId());
            writeMap(data, relation.metadata());
            relationsWritten++;
        }

        @Override
        public void close() throws IOException {
            if (nodesWritten != nodeCount) {
                throw new IllegalStateException(
                        "Wrote " + nodesWritten + " of the declared " + nodeCount + " nodes.");
            }
            if (!relationCountWritten) {
                data.writeInt(relationCount);
                relationCountWritten = true;
            }
            if (relationsWritten != relationCount) {
                throw new IllegalStateException(
                        "Wrote " + relationsWritten + " of the declared " + relationCount + " relations.");
            }
            data.close();
        }
    }

    /** Reads a whole file into memory. Convenience over {@link #reader}; large imports should
     *  stream with the reader instead. */
    public static GraphExportFile decode(InputStream in) {
        try (GraphFileReader reader = reader(in)) {
            List<GraphExportFile.ExportedNode> nodes = new ArrayList<>();
            GraphExportFile.ExportedNode node;
            while ((node = reader.nextNode()) != null) {
                nodes.add(node);
            }
            List<GraphExportFile.ExportedRelation> relations = new ArrayList<>();
            GraphExportFile.ExportedRelation relation;
            while ((relation = reader.nextRelation()) != null) {
                relations.add(relation);
            }
            return new GraphExportFile(nodes, relations);
        }
    }

    /**
     * Opens a streaming reader: the header (magic, version, node count) is validated immediately,
     * then nodes and relations are pulled one at a time, so a caller can import in segments
     * without ever holding the whole file. The format writes every node before any relation, so
     * consume nodes with {@link GraphFileReader#nextNode()} until it returns null, then relations
     * with {@link GraphFileReader#nextRelation()}.
     */
    public static GraphFileReader reader(InputStream in) {
        return new GraphFileReader(in);
    }

    public static final class GraphFileReader implements java.io.Closeable {

        private final DataInputStream data;
        private final int nodeCount;
        private int nodesRead;
        private int relationCount = -1;
        private int relationsRead;

        private GraphFileReader(InputStream in) {
            try {
                this.data = new DataInputStream(
                        limited(new GZIPInputStream(in), MAX_DECOMPRESSED_BYTES,
                                "The file inflates past " + (MAX_DECOMPRESSED_BYTES / (1024 * 1024)) + " MB."));
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
                this.nodeCount = readCount(data, "node count");
                if (nodeCount > MAX_NODES) {
                    throw new GraphTransferLimitException(
                            "The file contains " + nodeCount + " nodes; the limit is " + MAX_NODES + ".");
                }
            } catch (ZipException | EOFException e) {
                throw new InvalidGraphFileException("Not a DataHub graph export file.", e);
            } catch (IOException e) {
                throw new InvalidGraphFileException("Could not read the graph export file.", e);
            }
        }

        public int nodeCount() {
            return nodeCount;
        }

        /** The next node, or null once all nodes are consumed. */
        public GraphExportFile.ExportedNode nextNode() {
            if (nodesRead >= nodeCount) {
                return null;
            }
            try {
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
                nodesRead++;
                return new GraphExportFile.ExportedNode(
                        externalId, name, description, source, isRoot, geoJson, dataSetExternalId,
                        labels, metadata);
            } catch (EOFException e) {
                throw new InvalidGraphFileException("Not a DataHub graph export file.", e);
            } catch (IOException e) {
                throw new InvalidGraphFileException("Could not read the graph export file.", e);
            }
        }

        /** The next relation, or null once all are consumed. Call only after nodes are exhausted. */
        public GraphExportFile.ExportedRelation nextRelation() {
            if (nodesRead < nodeCount) {
                throw new IllegalStateException("Consume every node before reading relations.");
            }
            try {
                if (relationCount == -1) {
                    relationCount = readCount(data, "relation count");
                    if (relationCount > MAX_RELATIONS) {
                        throw new GraphTransferLimitException(
                                "The file contains " + relationCount + " relations; the limit is "
                                        + MAX_RELATIONS + ".");
                    }
                }
                if (relationsRead >= relationCount) {
                    return null;
                }
                relationsRead++;
                return new GraphExportFile.ExportedRelation(
                        readString(data), readString(data), readString(data), readString(data),
                        readString(data), readMap(data));
            } catch (EOFException e) {
                throw new InvalidGraphFileException("Not a DataHub graph export file.", e);
            } catch (IOException e) {
                throw new InvalidGraphFileException("Could not read the graph export file.", e);
            }
        }

        @Override
        public void close() {
            try {
                data.close();
            } catch (IOException e) {
                // Closing a fully- or partially-consumed decode stream has nothing left to fail on
                // that matters to the caller; the read path reported real problems already.
            }
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

    /**
     * Wraps {@code in} so that reading more than {@code maxBytes} throws a
     * {@link GraphTransferLimitException} with {@code message}. Used both under the gzip stream
     * (compressed upload cap — Content-Length can lie or be absent on chunked uploads) and over it
     * (decompressed cap, the gzip-bomb guard).
     */
    public static InputStream limited(InputStream in, long maxBytes, String message) {
        return new LimitedInputStream(in, maxBytes, message);
    }

    static final class LimitedInputStream extends FilterInputStream {

        private final long maxBytes;
        private final String message;
        private long read;

        LimitedInputStream(InputStream in, long maxBytes, String message) {
            super(in);
            this.maxBytes = maxBytes;
            this.message = message;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(long n) {
            read += n;
            if (read > maxBytes) {
                throw new GraphTransferLimitException(message);
            }
        }
    }
}
