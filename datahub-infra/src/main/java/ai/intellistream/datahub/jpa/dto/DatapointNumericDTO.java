// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

@Getter
@Setter
@NoArgsConstructor
public class DatapointNumericDTO implements DatapointDTO {

    private ZonedDateTime timestamp;
    private BigDecimal value;

    public DatapointNumericDTO(ZonedDateTime timestamp, BigDecimal value) {
        this.timestamp = timestamp;
        this.value = value;
    }
}
