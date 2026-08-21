// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
abstract class AbstractTenant {

    @JsonProperty("user")
    private String username;

    private String password;

    @JsonProperty("database")
    private String databaseName;

    private String host;
}
