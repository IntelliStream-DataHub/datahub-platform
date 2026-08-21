// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.jpa.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZonedDateTime;

@Getter
@Setter
@NoArgsConstructor
public class DatapointFloatDTO implements DatapointDTO {

    private ZonedDateTime timestamp;
    private double value;

    public DatapointFloatDTO(ZonedDateTime timestamp, double value) {
        this.timestamp = timestamp;
        this.value = value;
    }
}
