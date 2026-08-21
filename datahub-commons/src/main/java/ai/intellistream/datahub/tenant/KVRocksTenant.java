// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-tenant KVRocks connection, loaded from the tenant's Vault entry under the {@code kvrocks} key:
 *
 * <pre>
 * "kvrocks": { "host": "10.0.0.10", "port": 6666, "password": "..." }
 * </pre>
 *
 * Each tenant has exactly one KVRocks instance. This is the event-id index used by
 * {@code KVRocksService}. KVRocks supports a password only (no username); {@code password} is
 * optional, and when absent the connection is unauthenticated.
 */
@Data
@NoArgsConstructor
public class KVRocksTenant {
    private String host;
    private Integer port;
    private String password;
}
