// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;
import tools.jackson.databind.annotation.JsonSerialize;
import ai.intellistream.datahub.json.ToStringSerializer;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Data
@Getter @Setter
public class DataCollection<T> {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String externalId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String nextCursor;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String unit;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String unitExternalId;

    private List<T> datapoints = new ArrayList<>();
}
