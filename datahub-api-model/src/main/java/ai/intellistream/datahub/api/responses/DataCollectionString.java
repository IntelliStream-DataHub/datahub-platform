// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import ai.intellistream.datahub.helpers.datetime.DateTimeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Data
public class DataCollectionString {

    private Collection<DatapointString> datapoints;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String externalId;
    private String valueType;

    private String inclusiveBegin;
    private String exclusiveEnd;

    public <T> Map<LocalDateTime, T> datapointsToMap(Function<DatapointString, T> valueMapper) {
        return datapoints.stream()
                .collect(Collectors.toMap(
                        // ISO-8601 or an epoch, resolved the same way every other wire timestamp is.
                        dp -> DateTimeHandler.fromEpochUTCTime(dp.getTimestamp()),
                        valueMapper
                ));
    }

}
