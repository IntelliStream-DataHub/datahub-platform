// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import ai.intellistream.datahub.config.VaultClientFactory;
import ai.intellistream.datahub.config.VaultProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads one tenant's model configuration, from {@code <mount>/tenant-llm/<org-id>}.
 *
 * <h3>Why not a block inside {@code tenant-resources}</h3>
 * Because this is the one piece of tenant configuration a person will eventually edit, and
 * {@code tenant-resources} is a single secret holding every tenant's database credentials. Vault
 * cannot narrow a write within a secret — ACL policies are path-based, and KV plugins do not
 * support the {@code allowed_parameters} family at all — so "may update
 * {@code tenant-resources.acme.llm} and nothing else" is not expressible however it is phrased.
 * Its own path is the only shape in which Vault could ever enforce that boundary.
 *
 * <p>Nothing writes it yet: today it is placed by an operator, and reads need no policy change
 * because the AppRole already reads the whole mount. Putting it in the right place now means the
 * write path is a policy line rather than a data migration for everyone who configured it early.
 *
 * <h3>Keyed by org id, not org name</h3>
 * {@code tenant-resources} is keyed by organization <em>name</em>, which only this package ever
 * sees. Every request instead carries an org id — {@code TenantContext}, {@code UserSession} — so
 * keying on that means the write path needs no name lookup, and renaming an organization does not
 * strand its configuration.
 */
@Slf4j
@Service
public class TenantLlmStore {

    private final JsonMapper jsonMapper;
    private final VaultProperties vault;
    private final HttpClient httpClient;

    public TenantLlmStore(JsonMapper jsonMapper, VaultProperties vault) {
        this.jsonMapper = jsonMapper;
        this.vault = vault;
        // Shares the keystore/truststore the startup loader used, so a Vault listener requiring
        // mutual TLS keeps accepting these calls after boot — same reasoning as TenantConfigService.
        HttpClient.Builder builder = HttpClient.newBuilder();
        VaultClientFactory.sslContext(vault).ifPresent(builder::sslContext);
        this.httpClient = builder.build();
    }

    /**
     * A tenant's model configuration, or null if it has none.
     *
     * <p>Absent is the ordinary case, not an error: a tenant without its own model uses the
     * deployment default, which is what every tenant did before any of this existed. A Vault
     * failure is logged and also returns null — falling back to the deployment default is a far
     * better outcome than failing every turn for that tenant.
     */
    public TenantLlm read(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(dataUrl(orgId)))
                    .header("X-Vault-Token", token())
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null; // never configured, or deleted
            }
            if (response.statusCode() != 200) {
                log.warn("Vault returned HTTP {} reading the model config for tenant {}",
                        response.statusCode(), orgId);
                return null;
            }
            Map<String, Object> body =
                    jsonMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
            // KV v2 nests the secret at data.data.
            if (body.get("data") instanceof Map<?, ?> outer
                    && outer.get("data") instanceof Map<?, ?> inner) {
                return jsonMapper.convertValue(inner, TenantLlm.class);
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Could not read the model config for tenant {}: {}", orgId, e.getMessage());
            return null;
        }
    }


    private String dataUrl(String orgId) {
        return vault.address() + "/v1/" + vault.secretName() + "/data/tenant-llm/" + orgId;
    }

    /**
     * An AppRole login per call.
     *
     * <p>Not cached, and that is a deliberate trade rather than an oversight: reads happen once per
     * tenant on the five-minute refresh and writes happen when a person saves a form, so the login
     * is never in a hot path. Caching a token means owning its TTL and its renewal, which is real
     * machinery to get subtly wrong for no measurable gain here.
     */
    private String token() throws Exception {
        Map<String, String> login = new HashMap<>();
        login.put("role_id", vault.roleId());
        login.put("secret_id", vault.secretId());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(vault.address() + "/v1/auth/approle/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(login)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body =
                jsonMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        if (body.get("auth") instanceof Map<?, ?> auth && auth.get("client_token") instanceof String token) {
            return token;
        }
        throw new IllegalStateException("Vault AppRole login did not return a token (HTTP "
                + response.statusCode() + ")");
    }
}
