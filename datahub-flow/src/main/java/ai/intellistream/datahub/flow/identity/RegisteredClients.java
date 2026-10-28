// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import ai.intellistream.datahub.flow.access.CallerAccess;
import ai.intellistream.datahub.flow.flows.FlowRepository.Identity;
import ai.intellistream.datahub.flow.web.FlowProblems;
import ai.intellistream.datahub.models.me.MeDataSets;
import ai.intellistream.datahub.models.me.MeGrants;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.client.MeService.Access;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import ai.intellistream.datahub.tenant.Tenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantConfigWriter;
import ai.intellistream.datahub.tenant.TenantFlowSection;
import ai.intellistream.datahub.tenant.TenantFlowSection.ClientCredentials;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The clients a tenant admin registered for flows to run as ({@code flow.client.<name>.*} in the
 * tenant's settings), and the <em>superset check</em> that keeps assigning one from being a way
 * around the dataset ACL: whoever assigns a client must hold every group the client holds.
 *
 * <p>The check asks datahub-api — {@code GET /me/grants} with a token taken for the client, and the
 * same with the assigner's own token — so groups are read the one way the platform reads them. An
 * admin ({@code DATAHUB_ADMIN}, a realm role, never a group) may assign any client; a client that is
 * itself an admin can only be assigned by an admin.
 */
@Slf4j
@Component
public class RegisteredClients {

    private static final Duration API_TIMEOUT = Duration.ofSeconds(10);

    private final Function<String, Tenant> tenants;
    private final TenantConfigWriter tenantConfig;
    private final KeycloakTokens keycloak;
    private final CallerAccess callers;
    private final String apiBaseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(API_TIMEOUT).build();

    @Autowired
    public RegisteredClients(TenantConfigService tenants, TenantConfigWriter tenantConfig,
                             KeycloakTokens keycloak, CallerAccess callers,
                             @Value("${datahub.api.url:http://localhost:8081}") String apiBaseUrl) {
        this(tenants::getConfig, tenantConfig, keycloak, callers, apiBaseUrl);
    }

    RegisteredClients(Function<String, Tenant> tenants, TenantConfigWriter tenantConfig,
                      KeycloakTokens keycloak, CallerAccess callers, String apiBaseUrl) {
        this.tenants = tenants;
        this.tenantConfig = tenantConfig;
        this.keycloak = keycloak;
        this.callers = callers;
        this.apiBaseUrl = apiBaseUrl;
    }

    /**
     * The scope a token for this tenant asks for: the tenant's organization pinned by its alias, so
     * that the token carries exactly the one organization claim the api requires. The organization's
     * alias in Keycloak is the tenant's organization name.
     */
    public String organizationScope(String tenantId) {
        return "organization:" + tenant(tenantId).getOrganizationName();
    }

    /**
     * The names of the tenant's registered clients, sorted: what a deployer picks from. Names only;
     * which of them the caller may actually assign is decided by {@link #assign} when they do.
     */
    public List<String> names(String tenantId) {
        try {
            return List.copyOf(TenantFlowSection.clientIds(
                    tenantConfig.current(tenant(tenantId).getOrganizationName())).keySet());
        } catch (IllegalStateException e) {
            log.warn("Could not read the tenant's flow clients from Vault: {}", e.getMessage());
            throw FlowProblems.apiUnavailable("The registered clients could not be listed: the "
                    + "configuration store did not answer. Retry shortly.");
        }
    }

    /**
     * Checks that the current caller may have flow runs act as client {@code name} — the client is
     * registered, Keycloak accepts its credentials, it may write {@code dataSetId}, and the caller
     * holds every group it holds — and returns it as an identity.
     */
    public Identity assign(String tenantId, String name, long dataSetId) {
        if (name == null || !TenantFlowSection.NAME.matcher(name).matches()) {
            throw FlowProblems.badRequest("runAs names a registered client: lowercase letters, digits "
                    + "and dashes.");
        }
        String token;
        try {
            token = tokens(tenantId, name).accessToken();
        } catch (KeycloakTokens.Rejected e) {
            throw FlowProblems.badRequest("Client " + name + " cannot be used: " + e.getMessage());
        } catch (KeycloakTokens.Unavailable e) {
            throw FlowProblems.identityProviderUnavailable("Keycloak could not be asked for a token "
                    + "for client " + name + ". Retry shortly.");
        }

        MeGrants client;
        MeGrants caller;
        MeDataSets clientWrites;
        try {
            DatahubClient asClient = as(token);
            client = asClient.me().grants();
            clientWrites = asClient.me().datasets(Access.WRITE);
            caller = callers.client().me().grants();
        } catch (DatahubApiException e) {
            if (e.statusCode() == 401) {
                throw FlowProblems.badRequest("datahub-api refused the token Keycloak issued for client "
                        + name + "; is it a member of this organization with DATAHUB_ACCESS?");
            }
            throw CallerAccess.translate(e);
        }

        List<String> missing = missing(client, caller);
        if (!missing.isEmpty()) {
            throw FlowProblems.forbidden("Client " + name + " holds grants you do not, so you cannot "
                    + "have flows run as it: " + String.join(", ", missing) + ".");
        }
        if (!clientWrites.all() && !clientWrites.ids().contains(dataSetId)) {
            throw FlowProblems.datasetForbidden("Client " + name + " may not write the flow's dataset, "
                    + "where a failed run raises its event.");
        }
        return Identity.client(name);
    }

    /**
     * What the client holds that the caller does not: nothing when the caller is an admin, its admin
     * role when the client is one and the caller is not, and otherwise the client's groups missing
     * from the caller's.
     */
    static List<String> missing(MeGrants client, MeGrants caller) {
        if (caller.admin()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        if (client.admin()) {
            missing.add("DATAHUB_ADMIN");
        }
        Set<String> held = new HashSet<>(caller.groups());
        client.groups().stream().filter(g -> !held.contains(g)).sorted().forEach(missing::add);
        return missing;
    }

    /**
     * A token for client {@code name}, with its credentials read from the tenant's settings now, so
     * a client whose secret was just rotated is taken with the new one.
     *
     * @throws KeycloakTokens.Rejected when the client is not registered or Keycloak refuses it
     */
    public KeycloakTokens.Tokens tokens(String tenantId, String name) {
        Tenant tenant = tenant(tenantId);
        ClientCredentials credentials = TenantFlowSection.clientCredentials(
                tenantConfig.current(tenant.getOrganizationName()), name);
        if (credentials == null) {
            throw new KeycloakTokens.Rejected("No client " + name + " is registered in the flow settings.");
        }
        return keycloak.clientCredentials(credentials.clientId(), credentials.clientSecret(),
                "openid " + organizationScope(tenantId));
    }

    private DatahubClient as(String token) {
        return DatahubClient.create(DatahubConfig.builder()
                .baseUrl(apiBaseUrl)
                .token(token)
                .requestTimeout(API_TIMEOUT)
                .build(), http);
    }

    private Tenant tenant(String tenantId) {
        Tenant tenant = tenants.apply(tenantId);
        if (tenant == null) {
            throw new IllegalStateException("No tenant " + tenantId);
        }
        return tenant;
    }
}
