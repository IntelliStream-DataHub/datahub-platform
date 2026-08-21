// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse;

import ai.intellistream.datahub.api.responses.DataCollectionBin;
import ai.intellistream.datahub.api.responses.DataCollectionString;
import ai.intellistream.datahub.api.responses.DataWrapperBin;
import ai.intellistream.datahub.api.responses.DataWrapperMessage;
import ai.intellistream.datahub.api.responses.DatapointBin;
import ai.intellistream.datahub.api.responses.DatapointString;
import ai.intellistream.datahub.helpers.datetime.DateTimeHandler;
import ai.intellistream.datahub.jpa.domains.TimeseriesValueType;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Bridges the human-facing {@link DataWrapperMessage} (string timestamp + value) and the binary
 * {@link DataWrapperBin} (epoch-millis timestamp + ClickHouse RowBinary value-bytes) used on the
 * all-datapoints topic.
 *
 * <ul>
 *   <li>{@link #toBinary} runs on the producer: it parses each timestamp once and encodes each value
 *       with {@link DatapointValueCodec}, so the consumer never re-parses on the hot path.</li>
 *   <li>{@link #toStringMessage} runs on the consumer, only for the subscription fan-out subset, so
 *       WebSocket subscribers keep receiving the existing string shape.</li>
 * </ul>
 */
public final class DatapointBinaryConverter {

    private DatapointBinaryConverter() {}

    public static DataWrapperBin toBinary(DataWrapperMessage msg) {
        DataWrapperBin bin = new DataWrapperBin();
        bin.setEventAction(msg.getEventAction());
        bin.setEventObject(msg.getEventObject());
        bin.setTenantId(msg.getTenantId());

        List<DataCollectionBin> items = new ArrayList<>();
        if (msg.getItems() != null) {
            for (DataCollectionString dc : msg.getItems()) {
                DataCollectionBin b = new DataCollectionBin();
                b.setId(dc.getId());
                b.setExternalId(dc.getExternalId());
                b.setValueType(dc.getValueType());
                b.setInclusiveBegin(dc.getInclusiveBegin());
                b.setExclusiveEnd(dc.getExclusiveEnd());

                if (dc.getDatapoints() != null && !dc.getDatapoints().isEmpty()) {
                    int typeId = TimeseriesValueType.getValueTypeId(dc.getValueType());
                    List<DatapointBin> dps = new ArrayList<>(dc.getDatapoints().size());
                    for (DatapointString dp : dc.getDatapoints()) {
                        long ts = DateTimeHandler.toEpochUTCTime(dp.getTimestamp());
                        dps.add(new DatapointBin(ts, DatapointValueCodec.encode(dp.getValue(), typeId)));
                    }
                    b.setDatapoints(dps);
                }
                items.add(b);
            }
        }
        bin.setItems(items);
        return bin;
    }

    public static DataWrapperMessage toStringMessage(DataWrapperBin bin) {
        List<DataCollectionString> items = new ArrayList<>();
        if (bin.getItems() != null) {
            for (DataCollectionBin b : bin.getItems()) {
                DataCollectionString dc = new DataCollectionString();
                dc.setId(b.getId());
                dc.setExternalId(b.getExternalId());
                dc.setValueType(b.getValueType());
                dc.setInclusiveBegin(b.getInclusiveBegin());
                dc.setExclusiveEnd(b.getExclusiveEnd());

                Collection<DatapointBin> binDps = b.getDatapoints();
                if (binDps != null && !binDps.isEmpty()) {
                    int typeId = TimeseriesValueType.getValueTypeId(b.getValueType());
                    List<DatapointString> dps = new ArrayList<>(binDps.size());
                    for (DatapointBin dp : binDps) {
                        DatapointString s = new DatapointString();
                        // Subscribers get an ISO-8601 UTC timestamp (e.g. 2024-08-30T22:00:00Z),
                        // the same shape they received before the binary transport.
                        s.setTimestamp(Instant.ofEpochMilli(dp.getTimestamp())
                                .atOffset(ZoneOffset.UTC)
                                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                        s.setValue(DatapointValueCodec.decodeToString(dp.getValue(), typeId));
                        dps.add(s);
                    }
                    dc.setDatapoints(dps);
                }
                items.add(dc);
            }
        }
        return new DataWrapperMessage(bin.getEventObject(), bin.getEventAction(), items, bin.getTenantId());
    }
}
