// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;

import ai.intellistream.datahub.pulsar.EventAction;
import ai.intellistream.datahub.pulsar.EventObject;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collection;

/**
 * Binary envelope sent on the all-datapoints topic — the binary counterpart of
 * {@link DataWrapperMessage}. The producer encodes once; the consumer streams the value-bytes into
 * ClickHouse and only decodes back to {@link DataWrapperMessage} for the subscription fan-out.
 */
@Data
@NoArgsConstructor
public class DataWrapperBin {
    private EventAction eventAction;
    private EventObject eventObject;
    private Collection<DataCollectionBin> items;
    private String tenantId;
}
