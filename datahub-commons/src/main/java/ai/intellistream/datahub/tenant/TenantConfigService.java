// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class TenantConfigService {

    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher eventPublisher;
    @Value("${vault.address}")
    private String vaultUri;

    @Value("${vault.role-id}")
    private String appRoleId;

    @Value("${vault.secret-id}")
    private String appRoleSecretId;

    @Value("${vault.secret-name:intellistream-datahub}")
    private String secretName;

    // Application-wide defaults for feature flags a tenant's Vault `tenant-config` block
    // doesn't set explicitly. Vault wins per tenant; these fill the gaps.
    @Value("${datahub.features.policy:true}")
    private boolean policyFeatureDefault;

    @Value("${datahub.features.streaming:true}")
    private boolean streamingFeatureDefault;

    // Chat is a gated, billed AI add-on, so it defaults OFF: a tenant only gets it when its Vault
    // tenant-config sets chat=true (or the deployment overrides this default).
    @Value("${datahub.features.chat:false}")
    private boolean chatFeatureDefault;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    // Fast in-memory cache. volatile (not final) so refreshCache() can publish a new
    // fully-built map with a single atomic reference swap — readers never see a
    // half-populated map mid-refresh.
    public volatile Map<String, Tenant> cachedTenants = new ConcurrentHashMap<>();

    public TenantConfigService(JsonMapper jsonMapper, ApplicationEventPublisher eventPublisher) {
        this.jsonMapper = jsonMapper;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    public void init() {
        refreshCache(); // Load immediately after dependency injection
    }

    public Tenant getConfig(String tenantId) {
        // 1. Fast Path: Memory
        if (cachedTenants.containsKey(tenantId)) {
            return cachedTenants.get(tenantId);
        }
        // 2. Slow Path: Emergency Refresh (Cache Miss)
        log.warn("Cache miss for {}, reloading from Vault...", tenantId);
        refreshCache();
        return cachedTenants.get(tenantId);
    }

    private String getVaultToken() throws Exception {
        // Authenticate with AppRole to get a token
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("role_id", appRoleId);
        loginRequest.put("secret_id", appRoleSecretId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(vaultUri + "/v1/auth/approle/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(loginRequest)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> loginResponse =
                jsonMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        //noinspection unchecked
        return (String) ((Map<String, Object>) loginResponse.get("auth")).get("client_token");
    }

    @Scheduled(fixedRate = 300000)
    public void refreshCache() {
        try {
            String vaultToken = getVaultToken();
            String url = vaultUri + "/v1/"+ secretName+ "/data/tenant-resources";

            Map<String, Tenant> tenantData = fetchDataFromVault(url, vaultToken);
            if (tenantData != null) {
                // Snapshot existing tenant IDs so we can detect newcomers after the refresh.
                Set<String> previousIds = Set.copyOf(cachedTenants.keySet());

                // Build the new view fully, then publish it with a single reference swap.
                // Mutating the live map in place (clear() + put) would let concurrent
                // getConfig() readers observe a transiently empty/partial map and take the
                // slow emergency-Vault-reload path (or return null for a valid tenant).
                Map<String, Tenant> refreshed = new ConcurrentHashMap<>();
                tenantData.forEach((name, tenant) -> {
                    tenant.setOrganizationName(name);
                    applyFeatureDefaults(tenant.getFeatures());
                    refreshed.put(tenant.getOrganizationId(), tenant);
                });
                cachedTenants = refreshed;
                log.info("Loaded {} tenants.", refreshed.size());

                // Publish synchronously so listeners (e.g. Flyway) finish before any caller
                // proceeds to use the new tenant's DataSource.
                for (Tenant tenant : refreshed.values()) {
                    if (!previousIds.contains(tenant.getOrganizationId())) {
                        log.info("New tenant detected: {}", tenant.getOrganizationId());
                        eventPublisher.publishEvent(new TenantAddedEvent(tenant));
                    }
                }

                // Tenants that disappeared from Vault — let listeners release per-tenant resources
                // (e.g. cached ClickHouse clients). Config *changes* need no event: consumers
                // re-resolve from the swapped map on their next access.
                for (String oldId : previousIds) {
                    if (!refreshed.containsKey(oldId)) {
                        log.info("Tenant removed: {}", oldId);
                        eventPublisher.publishEvent(new TenantRemovedEvent(oldId));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Vault refresh failed: {}", e.getMessage(), e);
        }
    }

    void applyFeatureDefaults(TenantFeatures features) {
        if (features.getPolicyFeatureEnabled() == null) {
            features.setPolicyFeatureEnabled(policyFeatureDefault);
        }
        if (features.getStreamingFeatureEnabled() == null) {
            features.setStreamingFeatureEnabled(streamingFeatureDefault);
        }
        if (features.getChatFeatureEnabled() == null) {
            features.setChatFeatureEnabled(chatFeatureDefault);
        }
    }

    private Map<String, Tenant> fetchDataFromVault(String url, String vaultToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Vault-Token", vaultToken)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body =
                jsonMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
        // Vault structure: body.data.data
        if (body != null && body.get("data") instanceof Map<?, ?> outerData) {
            Object innerData = outerData.get("data");
            if (innerData != null) {
                // In Jackson 3, JsonMapper.convertValue is the standard way to map objects
                return jsonMapper.convertValue(innerData, new TypeReference<Map<String, Tenant>>() {});
            }
        }
        return null;
    }
}
