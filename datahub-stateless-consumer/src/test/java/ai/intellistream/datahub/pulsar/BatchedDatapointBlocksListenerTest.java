// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.api.binary.ArrowIpc;
import ai.intellistream.datahub.api.binary.ArrowSchemaCanon;
import ai.intellistream.datahub.api.binary.DatapointFrame;
import ai.intellistream.datahub.api.binary.DatapointFrameWriter;
import ai.intellistream.datahub.api.binary.DatapointValueType;
import ai.intellistream.datahub.api.binary.FrameLimits;
import ai.intellistream.datahub.api.binary.ZstdPayloadCodec;
import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.clickhouse.ClickHouseDatapointService;
import ai.intellistream.datahub.repositories.subscription.SubscriptionRepository;
import ai.intellistream.datahub.subscription.SubscriptionCache;
import ai.intellistream.datahub.tenant.TenantConfigService;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Messages;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The block listener's batch handling with the broker mocked: frames of one tenant and value type
 * are relayed into one stream, an in-band compressed frame byte for byte and with the HTTP body
 * left uncompressed, each group is acked or nacked as a whole, a malformed message is nacked on
 * its own, and only frames with a subscribed series are decoded for the fan-out.
 */
class BatchedDatapointBlocksListenerTest {

    static final ZstdPayloadCodec ZSTD = new ZstdPayloadCodec(1);
    static final byte[] END_OF_STREAM = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0};

    ClickHouseDatapointService clickHouse;
    SubscriptionCache cache;
    SubscriptionFanout fanout;
    Consumer<byte[]> consumer;
    BatchedDatapointBlocksListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        clickHouse = mock(ClickHouseDatapointService.class);
        cache = new SubscriptionCache(mock(SubscriptionRepository.class), mock(TenantConfigService.class));
        fanout = mock(SubscriptionFanout.class);
        consumer = mock(Consumer.class);
        listener = new BatchedDatapointBlocksListener(mock(PulsarClient.class), clickHouse, mock(TopicNames.class), cache, fanout);
        ReflectionTestUtils.setField(listener, "consumer", consumer);
    }

    /** As the SDK sends a frame: every buffer zstd in-band. */
    private static byte[] frame(DatapointValueType type, long id, int rows) {
        DatapointFrameWriter w = DatapointFrameWriter.forType(type).series(id, "s" + id);
        for (int r = 0; r < rows; r++) {
            w.add(id, 1_700_000_000_000L + r * 1000L, type.carriesText() ? "t" + r : String.valueOf(id * 100 + r));
        }
        return w.build(ZSTD);
    }

    /** The same rows as a {@link FrameLimits#COMPRESSION_ZSTD} frame: the whole stream zstd, its buffers plain. */
    private static byte[] wholePayloadFrame(DatapointValueType type, long id, int rows) {
        DatapointFrame inBand = DatapointFrame.parseAll(frame(type, id, rows), ZSTD).get(0);
        byte[] raw = ArrowIpc.writeStream(ArrowSchemaCanon.of(type), inBand.batches().stream().map(ArrowIpc.Batch::toData).toList());
        byte[] compressed = ZSTD.compress(raw);
        byte[] name = ("s" + id).getBytes(StandardCharsets.UTF_8);
        int directoryLength = 8 + 1 + name.length;
        ByteBuffer f = ByteBuffer.allocate(FrameLimits.HEADER_BYTES + directoryLength + compressed.length).order(ByteOrder.LITTLE_ENDIAN);
        f.put(FrameLimits.MAGIC).put((byte) FrameLimits.VERSION).put((byte) type.id())
                .put((byte) FrameLimits.CODEC_ARROW_IPC).put((byte) FrameLimits.COMPRESSION_ZSTD);
        f.putInt(rows).putInt(1).putInt(directoryLength).putInt(compressed.length).putInt(raw.length);
        f.putLong(id).put((byte) name.length).put(name);
        f.put(compressed);
        return f.array();
    }

    @SuppressWarnings("unchecked")
    private static Message<byte[]> message(String tenant, byte[] data) {
        Message<byte[]> m = mock(Message.class);
        when(m.getData()).thenReturn(data);
        when(m.getProperty("tenantId")).thenReturn(tenant);
        when(m.getMessageId()).thenReturn(mock(MessageId.class));
        return m;
    }

    private static Messages<byte[]> batch(List<Message<byte[]>> list) {
        return new Messages<>() {
            @Override
            public int size() {
                return list.size();
            }

            @Override
            public Iterator<Message<byte[]>> iterator() {
                return list.iterator();
            }
        };
    }

    /** The batches of an insert stream, their in-band buffers decompressed as ClickHouse would. */
    private static List<ArrowIpc.Batch> read(DatapointValueType type, byte[] stream) throws Exception {
        ArrowIpc.StreamReader reader = new ArrowIpc.StreamReader(ArrowSchemaCanon.of(type), new ByteArrayInputStream(stream),
                0, 1_000_000, 1 << 30, ZSTD);
        List<ArrowIpc.Batch> batches = new ArrayList<>();
        for (ArrowIpc.Batch b = reader.next(); b != null; b = reader.next()) {
            batches.add(b);
        }
        return batches;
    }

    @Test
    void relaysFramesPerTenantAndTypeAndAcksEachGroup() throws Exception {
        Message<byte[]> a1 = message("acme", frame(DatapointValueType.FLOAT32, 1, 300));
        Message<byte[]> a2 = message("acme", frame(DatapointValueType.FLOAT32, 2, 200));
        Message<byte[]> aText = message("acme", frame(DatapointValueType.TEXT, 3, 5));
        Message<byte[]> other = message("globex", frame(DatapointValueType.FLOAT32, 9, 10));

        listener.handleBlockMessages(batch(List.of(a1, a2, aText, other)));

        ArgumentCaptor<byte[]> streams = ArgumentCaptor.forClass(byte[].class);
        verify(clickHouse).insertArrowStream(eq("acme"), eq(DatapointValueType.FLOAT32), streams.capture(), eq(true));
        verify(clickHouse).insertArrowStream(eq("acme"), eq(DatapointValueType.TEXT), any(), eq(true));
        verify(clickHouse).insertArrowStream(eq("globex"), eq(DatapointValueType.FLOAT32), any(), eq(true));

        // One stream, the two frames' batches in order, as they came: no merge.
        List<ArrowIpc.Batch> relayed = read(DatapointValueType.FLOAT32, streams.getValue());
        assertThat(relayed).hasSize(2);
        assertThat(relayed.get(0).rows()).isEqualTo(300);
        assertThat(relayed.get(0).columns()[0].data().getLong(0)).isEqualTo(1L);
        assertThat(relayed.get(1).rows()).isEqualTo(200);
        assertThat(relayed.get(1).columns()[0].data().getLong(199 * 8)).isEqualTo(2L);
        assertThat(relayed.get(1).columns()[2].data().getFloat(199 * 4)).isEqualTo(2f * 100 + 199);

        verify(consumer).acknowledge(a1);
        verify(consumer).acknowledge(a2);
        verify(consumer).acknowledge(aText);
        verify(consumer).acknowledge(other);
        verify(consumer, never()).negativeAcknowledge(any(Message.class));
        // Nobody subscribed, so nothing was decoded for the fan-out.
        verify(fanout, never()).forward(any());
    }

    @Test
    void anInBandFrameReachesTheInsertAsItWasSent() throws Exception {
        byte[] built = frame(DatapointValueType.TEXT, 7, 40);
        DatapointFrame sent = DatapointFrame.parseEnvelopes(built).get(0);
        assertThat(sent.compression()).isEqualTo(FrameLimits.COMPRESSION_ARROW_ZSTD);

        listener.handleBlockMessages(batch(List.of(message("acme", built))));

        // The stream is the frame's own payload: the schema, its compressed record batch, the end
        // marker. Nothing was decompressed, and the insert is told so.
        ArgumentCaptor<byte[]> streams = ArgumentCaptor.forClass(byte[].class);
        verify(clickHouse).insertArrowStream(eq("acme"), eq(DatapointValueType.TEXT), streams.capture(), eq(true));
        assertThat(streams.getValue()).isEqualTo(sent.payloadBytes());
        assertThat(read(DatapointValueType.TEXT, streams.getValue()).get(0).columns()[2].utf8At(39)).isEqualTo("t39");
    }

    @Test
    void aWholePayloadFrameIsDecompressedOnceAndTheInsertCompressesTheBody() throws Exception {
        byte[] built = wholePayloadFrame(DatapointValueType.MIXED, 5, 6);
        assertThat(DatapointFrame.parseEnvelopes(built).get(0).compression()).isEqualTo(FrameLimits.COMPRESSION_ZSTD);
        Message<byte[]> whole = message("acme", built);
        Message<byte[]> inBand = message("acme", frame(DatapointValueType.MIXED, 6, 2));

        listener.handleBlockMessages(batch(List.of(whole, inBand)));

        ArgumentCaptor<byte[]> streams = ArgumentCaptor.forClass(byte[].class);
        verify(clickHouse).insertArrowStream(eq("acme"), eq(DatapointValueType.MIXED), streams.capture(), eq(false));
        byte[] stream = streams.getValue();
        byte[] plain = ArrowIpc.writeStream(ArrowSchemaCanon.of(DatapointValueType.MIXED),
                DatapointFrame.parseAll(built, ZSTD).get(0).batches().stream().map(ArrowIpc.Batch::toData).toList());
        // The plain batch leads, as written; the in-band one follows, still compressed.
        assertThat(stream).startsWith(java.util.Arrays.copyOf(plain, plain.length - END_OF_STREAM.length));
        List<ArrowIpc.Batch> relayed = read(DatapointValueType.MIXED, stream);
        assertThat(relayed).hasSize(2);
        assertThat(relayed.get(0).columns()[3].utf8At(5)).isEqualTo("t5");
        assertThat(relayed.get(1).columns()[0].data().getLong(0)).isEqualTo(6L);
        verify(consumer).acknowledge(whole);
        verify(consumer).acknowledge(inBand);
    }

    @Test
    void anInsertFailureNacksOnlyThatGroup() throws Exception {
        Message<byte[]> ok = message("acme", frame(DatapointValueType.FLOAT32, 1, 3));
        Message<byte[]> failing = message("acme", frame(DatapointValueType.TEXT, 2, 3));
        doThrow(new RuntimeException("ClickHouse insert failed")).when(clickHouse)
                .insertArrowStream(eq("acme"), eq(DatapointValueType.TEXT), any(), anyBoolean());

        listener.handleBlockMessages(batch(List.of(ok, failing)));

        verify(consumer).acknowledge(ok);
        verify(consumer).negativeAcknowledge(failing);
        verify(consumer, never()).acknowledge(failing);
    }

    @Test
    void aMessageFeedingTwoGroupsIsNackedWhenEitherFails() throws Exception {
        // TEXT's group runs first and succeeds; the message must not be acked on its account, or
        // the nack FLOAT's failure sends afterwards is ignored and FLOAT's rows are never retried.
        ByteArrayOutputStream both = new ByteArrayOutputStream();
        both.writeBytes(frame(DatapointValueType.TEXT, 1, 3));
        both.writeBytes(frame(DatapointValueType.FLOAT, 2, 3));
        Message<byte[]> mixed = message("acme", both.toByteArray());
        Message<byte[]> textOnly = message("acme", frame(DatapointValueType.TEXT, 3, 3));
        doThrow(new RuntimeException("ClickHouse insert failed")).when(clickHouse)
                .insertArrowStream(eq("acme"), eq(DatapointValueType.FLOAT), any(), anyBoolean());

        listener.handleBlockMessages(batch(List.of(mixed, textOnly)));

        verify(clickHouse).insertArrowStream(eq("acme"), eq(DatapointValueType.TEXT), any(), anyBoolean());
        verify(consumer, never()).acknowledge(mixed);
        verify(consumer).negativeAcknowledge(mixed);
        verify(consumer).acknowledge(textOnly);
        verify(consumer, never()).negativeAcknowledge(textOnly);
    }

    @Test
    void aMalformedMessageIsNackedAndTheRestProceed() throws Exception {
        Message<byte[]> good = message("acme", frame(DatapointValueType.BIGINT, 1, 3));
        Message<byte[]> garbage = message("acme", "not a frame at all, nowhere near one".getBytes());
        Message<byte[]> noTenant = message(null, frame(DatapointValueType.BIGINT, 2, 3));
        // A record batch whose continuation marker is gone: the relay's header walk refuses it.
        byte[] corrupt = frame(DatapointValueType.BIGINT, 3, 3);
        int batch = corrupt.length - DatapointFrame.parseEnvelopes(corrupt).get(0).payloadLength()
                + ArrowSchemaCanon.of(DatapointValueType.BIGINT).schemaMessage().length;
        corrupt[batch] = 0;
        Message<byte[]> broken = message("acme", corrupt);

        listener.handleBlockMessages(batch(List.of(good, garbage, noTenant, broken)));

        verify(consumer).negativeAcknowledge(garbage);
        verify(consumer).negativeAcknowledge(noTenant);
        verify(consumer).negativeAcknowledge(broken);
        verify(consumer).acknowledge(good);
        verify(clickHouse).insertArrowStream(eq("acme"), eq(DatapointValueType.BIGINT), any(), eq(true));
    }

    @Test
    void onlyFramesWithASubscribedSeriesAreDecodedForTheFanOut() throws Exception {
        cache.add("acme", 2L, "sub-x");
        Message<byte[]> m = message("acme", frame(DatapointValueType.MIXED, 1, 2));
        Message<byte[]> n = message("acme", frame(DatapointValueType.MIXED, 2, 2));

        listener.handleBlockMessages(batch(List.of(m, n)));

        ArgumentCaptor<DataWrapperMessage> forwarded = ArgumentCaptor.forClass(DataWrapperMessage.class);
        verify(fanout).forward(forwarded.capture());
        DataWrapperMessage out = forwarded.getValue();
        assertThat(out.getTenantId()).isEqualTo("acme");
        assertThat(out.getItems()).hasSize(1);
        var item = out.getItems().iterator().next();
        assertThat(item.getId()).isEqualTo(2L);
        assertThat(item.getExternalId()).isEqualTo("s2");
        // The JSON path sends the value-type row's own name, so this has to be the same string: a
        // subscriber cannot tell which ingest path a point came in by, and must not have to.
        // DatapointValueTypeParityTest is what pins that name to the row Flyway seeds.
        assertThat(item.getValueType()).isEqualTo("MIXED");
        assertThat(item.getValueType()).isEqualTo(DatapointValueType.MIXED.name());
        assertThat(item.getDatapoints()).hasSize(2);
        var first = item.getDatapoints().iterator().next();
        assertThat(first.getTimestamp()).isEqualTo("2023-11-14T22:13:20Z");
        assertThat(first.getValue()).isEqualTo("t0");
    }

    @Test
    @SuppressWarnings("unchecked")
    void initFailsFastWhenSubscribeFails() throws Exception {
        PulsarClient client = mock(PulsarClient.class);
        ConsumerBuilder<Object> builder = mock(ConsumerBuilder.class, Answers.RETURNS_SELF);
        when(client.newConsumer(any(Schema.class))).thenReturn(builder);
        when(builder.subscribe()).thenThrow(new PulsarClientException("broker unreachable"));
        TopicNames topicNames = mock(TopicNames.class);
        when(topicNames.getAllDatapointBlocksTopicName()).thenReturn("persistent://internal/datapoint-blocks/all-datapoint-blocks");

        BatchedDatapointBlocksListener fresh = new BatchedDatapointBlocksListener(client, null, topicNames, null, null);
        assertThatThrownBy(fresh::init).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("all-datapoint-blocks consumer");
        assertThat(new ArrayList<>()).isEmpty();
    }
}
