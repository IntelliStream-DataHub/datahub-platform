// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.responses.swaggerdto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.ZonedDateTime;

@Getter
@Setter
@AllArgsConstructor
@Schema(name="Data point", description="Data point with timestamp and value")
public class DatapointDTO {

    private long timestamp;
    private ZonedDateTime isoTime;
    private String value;

}
