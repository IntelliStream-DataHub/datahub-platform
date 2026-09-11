// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.pulsar;

import ai.intellistream.datahub.api.binary.ArrowIpc;
import ai.intellistream.datahub.api.binary.ArrowSchemaCanon;
import ai.intellistream.datahub.api.binary.DatapointFrameWriter;
import ai.intellistream.datahub.api.binary.DatapointValueType;
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The block listener's batch handling with the broker mocked: frames of one tenant and value type
 * merge into one stream of large batches, each group is acked or nacked as a whole, a malformed
 * message is nacked on its own, and only series with subscribers are decoded for the fan-out.
 */
class BatchedDatapointBlocksListenerTest {

    static final ZstdPayloadCodec ZSTD = new ZstdPayloadCodec(1);

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

    private static byte[] frame(DatapointValueType type, long id, int rows) {
        DatapointFrameWriter w = DatapointFrameWriter.forType(type).series(id, "s" + id);
        for (int r = 0; r < rows; r++) {
            w.add(id, 1_700_000_000_000L + r * 1000L, type.carriesText() ? "t" + r : String.valueOf(id * 100 + r));
        }
        return w.build(ZSTD);
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

    @Test
    void mergesFramesPerTenantAndTypeAndAcksEachGroup() throws Exception {
        Message<byte[]> a1 = message("acme", frame(DatapointValueType.FLOAT32, 1, 300));
        Message<byte[]> a2 = message("acme", frame(DatapointValueType.FLOAT32, 2, 200));
        Message<byte[]> aText = message("acme", frame(DatapointValueType.TEXT, 3, 5));
        Message<byte[]> other = message("globex", frame(DatapointValueType.FLOAT32, 9, 10));

        listener.handleBlockMessages(batch(List.of(a1, a2, aText, other)));

        ArgumentCaptor<byte[]> streams = ArgumentCaptor.forClass(byte[].class);
        verify(clickHouse).insertArrowStream(eq("acme"), eq(DatapointValueType.FLOAT32), streams.capture());
        verify(clickHouse).insertArrowStream(eq("acme"), eq(DatapointValueType.TEXT), any());
        verify(clickHouse).insertArrowStream(eq("globex"), eq(DatapointValueType.FLOAT32), any());

        List<ArrowIpc.Batch> merged = ArrowIpc.readStream(ArrowSchemaCanon.of(DatapointValueType.FLOAT32), streams.getValue(), 0, 1_000_000);
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).rows()).isEqualTo(500);
        assertThat(merged.get(0).columns()[0].data().getLong(0)).isEqualTo(1L);
        assertThat(merged.get(0).columns()[0].data().getLong(499 * 8)).isEqualTo(2L);
        assertThat(merged.get(0).columns()[2].data().getFloat(499 * 4)).isEqualTo(2f * 100 + 199);

        verify(consumer).acknowledge(a1);
        verify(consumer).acknowledge(a2);
        verify(consumer).acknowledge(aText);
        verify(consumer).acknowledge(other);
        verify(consumer, never()).negativeAcknowledge(any(Message.class));
        // Nobody subscribed, so nothing was decoded for the fan-out.
        verify(fanout, never()).forward(any());
    }

    @Test
    void anInsertFailureNacksOnlyThatGroup() throws Exception {
        Message<byte[]> ok = message("acme", frame(DatapointValueType.FLOAT32, 1, 3));
        Message<byte[]> failing = message("acme", frame(DatapointValueType.TEXT, 2, 3));
        doThrow(new RuntimeException("ClickHouse insert failed")).when(clickHouse)
                .insertArrowStream(eq("acme"), eq(DatapointValueType.TEXT), any());

        listener.handleBlockMessages(batch(List.of(ok, failing)));

        verify(consumer).acknowledge(ok);
        verify(consumer).negativeAcknowledge(failing);
        verify(consumer, never()).acknowledge(failing);
    }

    @Test
    void aMalformedMessageIsNackedAndTheRestProceed() throws Exception {
        Message<byte[]> good = message("acme", frame(DatapointValueType.BIGINT, 1, 3));
        Message<byte[]> garbage = message("acme", "not a frame at all, nowhere near one".getBytes());
        Message<byte[]> noTenant = message(null, frame(DatapointValueType.BIGINT, 2, 3));
        byte[] corrupt = frame(DatapointValueType.BIGINT, 3, 3);
        corrupt[corrupt.length - 2] ^= 0x55;
        Message<byte[]> broken = message("acme", corrupt);

        listener.handleBlockMessages(batch(List.of(good, garbage, noTenant, broken)));

        verify(consumer).negativeAcknowledge(garbage);
        verify(consumer).negativeAcknowledge(noTenant);
        verify(consumer).negativeAcknowledge(broken);
        verify(consumer).acknowledge(good);
        verify(clickHouse).insertArrowStream(eq("acme"), eq(DatapointValueType.BIGINT), any());
    }

    @Test
    void onlySubscribedSeriesAreDecodedForTheFanOut() throws Exception {
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
        assertThat(item.getValueType()).isEqualTo("mixed");
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
