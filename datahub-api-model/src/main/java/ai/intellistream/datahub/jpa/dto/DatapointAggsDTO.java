// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.jpa.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZonedDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class DatapointAggsDTO {

    private ZonedDateTime timestamp;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double min;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double max;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double average;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double sum;

}
