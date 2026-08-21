// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Per-tenant Pulsar settings, loaded from the tenant's entry under the {@code pulsar} key:
 *
 * <pre>
 * "pulsar": { "tenant": "acme-datahub", "namespaces": ["subscriptions"],
 *             "service-url": "pulsar+ssl://...:6651", "admin-url": "https://...:8443" }
 * </pre>
 *
 * <p>Today only {@link #tenant} is read: it is the Pulsar tenant that hosts this customer's
 * subscription fan-out topic ({@code persistent://<tenant>/subscriptions/fanout}), so each
 * customer's live-subscription traffic is isolated in its own Pulsar tenant instead of a shared
 * public one. It is required for any tenant that uses subscriptions: when absent, callers throw
 * rather than silently sharing another tenant's fan-out topic.
 *
 * <p>{@link #namespaces}, {@link #serviceUrl} and {@link #adminUrl} are modeled to capture the
 * full Vault shape but are <b>not read yet</b> — they are reserved for the deferred multi-cluster
 * work (per-tenant Pulsar clusters). Until then every tenant is served by the shared cluster's
 * {@code PulsarClient}/{@code PulsarAdmin}.
 */
@Data
@NoArgsConstructor
public class PulsarTenant {

    private String tenant;

    private List<String> namespaces;

    @JsonProperty("service-url")
    private String serviceUrl;

    @JsonProperty("admin-url")
    private String adminUrl;
}
