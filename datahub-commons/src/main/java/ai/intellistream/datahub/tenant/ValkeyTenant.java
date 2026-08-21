// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-tenant Valkey connection, loaded from the tenant's Vault entry under the {@code valkey} key:
 *
 * <pre>
 * "valkey": { "host": "10.0.0.10", "port": 6379, "user": "datahub", "password": "..." }
 * </pre>
 *
 * Each tenant has exactly one Valkey instance. This is the data cache used by {@code ValkeyService};
 * it is distinct from the console's global session-store Valkey. Credentials are optional: when
 * {@code user} is absent the connection is unauthenticated.
 */
@Data
@NoArgsConstructor
public class ValkeyTenant {
    private String host;
    private Integer port;

    @JsonProperty("user")
    private String username;

    private String password;
}
