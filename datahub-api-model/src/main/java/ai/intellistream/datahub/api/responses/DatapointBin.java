// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Binary wire form of a datapoint on the all-datapoints Pulsar topic. The timestamp is epoch millis
 * (parsed once on the producer) and the value is the ClickHouse RowBinary value-bytes produced by
 * {@code DatapointValueCodec} — so the consumer streams them straight into ClickHouse without
 * re-parsing. The human-facing {@link DatapointString} (string timestamp + value) is still used by
 * the HTTP API, the WebSocket fan-out, Valkey and MCP.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DatapointBin {
    private long timestamp;
    private byte[] value;
}
