// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.binary;

import com.google.flatbuffers.FlatBufferBuilder;
import org.apache.arrow.flatbuf.Decimal;
import org.apache.arrow.flatbuf.Endianness;
import org.apache.arrow.flatbuf.Field;
import org.apache.arrow.flatbuf.FloatingPoint;
import org.apache.arrow.flatbuf.Int;
import org.apache.arrow.flatbuf.Message;
import org.apache.arrow.flatbuf.MessageHeader;
import org.apache.arrow.flatbuf.MetadataVersion;
import org.apache.arrow.flatbuf.Precision;
import org.apache.arrow.flatbuf.Schema;
import org.apache.arrow.flatbuf.TimeUnit;
import org.apache.arrow.flatbuf.Timestamp;
import org.apache.arrow.flatbuf.Type;
import org.apache.arrow.flatbuf.Utf8;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The one Arrow schema each value type is allowed to arrive in: field names, types and
 * nullability exactly as the ClickHouse table wants them, so the server casts nothing on insert.
 *
 * @see ArrowIpc why this package handles Arrow itself
 */
public final class ArrowSchemaCanon {

    /** The Arrow types the contract uses, with their fixed width in bytes (-1 for variable). */
    public enum Kind {
        INT64(8, 0, 0),
        FLOAT64(8, 0, 0),
        FLOAT32(4, 0, 0),
        TIMESTAMP_MS_UTC(8, 0, 0),
        DECIMAL_18_6(16, 18, 6),
        DECIMAL_9_4(16, 9, 4),
        UTF8(-1, 0, 0);

        final int width;
        final int precision;
        final int scale;

        Kind(int width, int precision, int scale) {
            this.width = width;
            this.precision = precision;
            this.scale = scale;
        }

        public int width() {
            return width;
        }

        public boolean fixedWidth() {
            return width > 0;
        }

        /** Buffers a record batch lists for this kind: validity plus data, or validity, offsets, data. */
        public int bufferCount() {
            return fixedWidth() ? 2 : 3;
        }
    }

    public record Column(String name, Kind kind, boolean nullable) {
    }

    public static final String ID_COLUMN = "timeseries_id";
    public static final String TIMESTAMP_COLUMN = "timestamp";
    public static final String VALUE_COLUMN = "value";
    public static final String VALUE_NUMERIC_COLUMN = "value_numeric";
    public static final String VALUE_TEXT_COLUMN = "value_text";
    public static final String TIMEZONE = "UTC";

    private static final Map<DatapointValueType, ArrowSchemaCanon> CANONS = new EnumMap<>(DatapointValueType.class);

    static {
        for (DatapointValueType t : DatapointValueType.values()) {
            CANONS.put(t, new ArrowSchemaCanon(t, columnsFor(t)));
        }
    }

    private final DatapointValueType valueType;
    private final List<Column> columns;
    private final byte[] schemaMessage;

    private ArrowSchemaCanon(DatapointValueType valueType, List<Column> columns) {
        this.valueType = valueType;
        this.columns = columns;
        this.schemaMessage = buildSchemaMessage(columns);
    }

    public static ArrowSchemaCanon of(DatapointValueType type) {
        return CANONS.get(type);
    }

    private static List<Column> columnsFor(DatapointValueType t) {
        Column id = new Column(ID_COLUMN, Kind.INT64, false);
        Column ts = new Column(TIMESTAMP_COLUMN, Kind.TIMESTAMP_MS_UTC, false);
        return switch (t) {
            case BIGINT -> List.of(id, ts, new Column(VALUE_COLUMN, Kind.INT64, false));
            case FLOAT -> List.of(id, ts, new Column(VALUE_COLUMN, Kind.FLOAT64, false));
            case FLOAT32 -> List.of(id, ts, new Column(VALUE_COLUMN, Kind.FLOAT32, false));
            case NUMERIC -> List.of(id, ts, new Column(VALUE_COLUMN, Kind.DECIMAL_18_6, false));
            case DECIMAL32 -> List.of(id, ts, new Column(VALUE_COLUMN, Kind.DECIMAL_9_4, false));
            case TEXT -> List.of(id, ts, new Column(VALUE_COLUMN, Kind.UTF8, false));
            case MIXED -> List.of(id, ts,
                    new Column(VALUE_NUMERIC_COLUMN, Kind.FLOAT64, true),
                    new Column(VALUE_TEXT_COLUMN, Kind.UTF8, true));
        };
    }

    public DatapointValueType valueType() {
        return valueType;
    }

    public List<Column> columns() {
        return columns;
    }

    /** Buffers a record batch of this schema lists. */
    public int bufferCount() {
        int n = 0;
        for (Column c : columns) {
            n += c.kind().bufferCount();
        }
        return n;
    }

    /** The encapsulated schema message every writer of this type emits, byte for byte. */
    public byte[] schemaMessage() {
        return schemaMessage.clone();
    }

    int schemaMessageLength() {
        return schemaMessage.length;
    }

    void writeSchemaMessage(java.io.ByteArrayOutputStream out) {
        out.write(schemaMessage, 0, schemaMessage.length);
    }

    /**
     * Whether a schema written by any Arrow implementation is this canon: same field count, names,
     * types with their parameters, nullability, no dictionary encoding, no children. Returns the
     * first difference as text, or null when it matches.
     */
    public String mismatch(Schema schema) {
        if (schema.endianness() != Endianness.Little) {
            return "big-endian schema";
        }
        if (schema.fieldsLength() != columns.size()) {
            return "expected " + columns.size() + " fields, found " + schema.fieldsLength();
        }
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            Field f = schema.fields(i);
            if (!c.name().equals(f.name())) {
                return "field " + i + " is named " + f.name() + ", expected " + c.name();
            }
            if (f.nullable() != c.nullable()) {
                return "field " + c.name() + " nullable=" + f.nullable() + ", expected " + c.nullable();
            }
            if (f.dictionary() != null) {
                return "field " + c.name() + " is dictionary-encoded";
            }
            if (f.childrenLength() != 0) {
                return "field " + c.name() + " has children";
            }
            String typeProblem = typeMismatch(c, f);
            if (typeProblem != null) {
                return typeProblem;
            }
        }
        return null;
    }

    private static String typeMismatch(Column c, Field f) {
        String prefix = "field " + c.name() + " ";
        return switch (c.kind()) {
            case INT64 -> {
                if (f.typeType() != Type.Int) yield prefix + "is not Int";
                Int t = (Int) f.type(new Int());
                yield t.bitWidth() == 64 && t.isSigned() ? null : prefix + "is not a signed 64-bit Int";
            }
            case FLOAT64 -> {
                if (f.typeType() != Type.FloatingPoint) yield prefix + "is not FloatingPoint";
                FloatingPoint t = (FloatingPoint) f.type(new FloatingPoint());
                yield t.precision() == Precision.DOUBLE ? null : prefix + "is not DOUBLE precision";
            }
            case FLOAT32 -> {
                if (f.typeType() != Type.FloatingPoint) yield prefix + "is not FloatingPoint";
                FloatingPoint t = (FloatingPoint) f.type(new FloatingPoint());
                yield t.precision() == Precision.SINGLE ? null : prefix + "is not SINGLE precision";
            }
            case TIMESTAMP_MS_UTC -> {
                if (f.typeType() != Type.Timestamp) yield prefix + "is not Timestamp";
                Timestamp t = (Timestamp) f.type(new Timestamp());
                if (t.unit() != TimeUnit.MILLISECOND) yield prefix + "is not a MILLISECOND timestamp";
                yield TIMEZONE.equals(t.timezone()) ? null : prefix + "timezone is " + t.timezone() + ", expected UTC";
            }
            case DECIMAL_18_6, DECIMAL_9_4 -> {
                if (f.typeType() != Type.Decimal) yield prefix + "is not Decimal";
                Decimal t = (Decimal) f.type(new Decimal());
                boolean ok = t.bitWidth() == 128 && t.precision() == c.kind().precision && t.scale() == c.kind().scale;
                yield ok ? null : prefix + "is Decimal(" + t.precision() + ", " + t.scale() + ", " + t.bitWidth()
                        + " bits), expected Decimal128(" + c.kind().precision + ", " + c.kind().scale + ")";
            }
            case UTF8 -> f.typeType() == Type.Utf8 ? null : prefix + "is not Utf8";
        };
    }

    private static byte[] buildSchemaMessage(List<Column> columns) {
        FlatBufferBuilder b = new FlatBufferBuilder(256);
        int[] fieldOffsets = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            int name = b.createString(c.name());
            byte typeType;
            int type;
            switch (c.kind()) {
                case INT64 -> {
                    typeType = Type.Int;
                    type = Int.createInt(b, 64, true);
                }
                case FLOAT64 -> {
                    typeType = Type.FloatingPoint;
                    type = FloatingPoint.createFloatingPoint(b, Precision.DOUBLE);
                }
                case FLOAT32 -> {
                    typeType = Type.FloatingPoint;
                    type = FloatingPoint.createFloatingPoint(b, Precision.SINGLE);
                }
                case TIMESTAMP_MS_UTC -> {
                    typeType = Type.Timestamp;
                    int tz = b.createString(TIMEZONE);
                    type = Timestamp.createTimestamp(b, TimeUnit.MILLISECOND, tz);
                }
                case DECIMAL_18_6, DECIMAL_9_4 -> {
                    typeType = Type.Decimal;
                    type = Decimal.createDecimal(b, c.kind().precision, c.kind().scale, 128);
                }
                case UTF8 -> {
                    typeType = Type.Utf8;
                    Utf8.startUtf8(b);
                    type = Utf8.endUtf8(b);
                }
                default -> throw new IllegalStateException(c.kind().toString());
            }
            int children = Field.createChildrenVector(b, new int[0]);
            fieldOffsets[i] = Field.createField(b, name, c.nullable(), typeType, type, 0, children, 0);
        }
        int fields = Schema.createFieldsVector(b, fieldOffsets);
        int schema = Schema.createSchema(b, Endianness.Little, fields, 0, 0);
        int message = Message.createMessage(b, MetadataVersion.V5, MessageHeader.Schema, schema, 0L, 0);
        b.finish(message);
        return ArrowIpc.encapsulate(b.sizedByteArray());
    }
}
