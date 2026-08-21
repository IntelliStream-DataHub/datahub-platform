// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collection;

/**
 * Binary counterpart of {@link DataCollectionString} for the all-datapoints topic. Mirrors it field
 * for field, except the datapoints are {@link DatapointBin} (binary). The delete time-range fields
 * stay strings — deletes are rare and not on the hot path.
 */
@Data
@NoArgsConstructor
public class DataCollectionBin {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String externalId;
    private String valueType;
    private Collection<DatapointBin> datapoints;
    private String inclusiveBegin;
    private String exclusiveEnd;
}
