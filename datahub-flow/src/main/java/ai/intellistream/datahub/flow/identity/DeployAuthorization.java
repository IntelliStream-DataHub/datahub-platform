// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import ai.intellistream.datahub.flow.access.Caller;
import ai.intellistream.datahub.flow.definition.InvalidFlowException;
import ai.intellistream.datahub.flow.flows.FlowRepository;
import ai.intellistream.datahub.flow.flows.FlowRepository.Identity;
import ai.intellistream.datahub.flow.flows.FlowRepository.StoredFlow;
import ai.intellistream.datahub.flow.flows.FlowService;
import ai.intellistream.datahub.flow.runs.RunService;
import ai.intellistream.datahub.flow.runs.RunService.DeploymentPlan;
import ai.intellistream.datahub.flow.web.FlowProblems;
import ai.intellistream.datahub.models.me.MeDataSets;
import ai.intellistream.datahub.sdk.client.DatahubClient;
import ai.intellistream.datahub.sdk.client.DatahubConfig;
import ai.intellistream.datahub.sdk.client.MeService.Access;
import ai.intellistream.datahub.sdk.http.DatahubApiException;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Deploying a flow as an OAuth authorization: the person deploying is sent to Keycloak, consents to
 * this service acting for them ({@code offline_access}, pinned to the tenant's organization), and
 * comes back to the callback with a code this service redeems for an <em>offline token</em>. Every
 * run of the deployment then exchanges that token for a short-lived access token and calls
 * datahub-api as that person.
 *
 * <p>The service keeps no session: the pending deployment travels as a signed {@link DeployState}.
 * There is no PKCE (a decision of 2026-10-12): the client is confidential, and the callback accepts
 * the token only if its subject is the person the state names. A code obtained from someone else's
 * sign-in therefore cannot be redeemed into another person's deployment — it carries the wrong
 * subject — and is revoked at once.
 *
 * <p>Before a token is stored, the callback also checks that it belongs to the right organization and
 * that it may write the flow's dataset, where a failed run raises its event. Only then is it stored
 * ({@code flow.token.<flowId>} in Vault), the token it replaces revoked, and the deployment written.
 */
@Slf4j
@Service
public class DeployAuthorization {

    static final String CALLBACK_PATH = "/flows/deployment/callback";
    static final Duration STATE_LIFETIME = Duration.ofMinutes(10);
    private static final Duration API_TIMEOUT = Duration.ofSeconds(10);

    private final FlowService flowService;
    private final FlowRepository flows;
    private final RunService runService;
    private final KeycloakTokens keycloak;
    private final FlowTokens offlineTokens;
    private final RegisteredClients clients;
    private final JwtDecoder jwtDecoder;
    private final JsonMapper json;
    private final String publicUrl;
    private final String consoleUrl;
    private final String apiBaseUrl;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(API_TIMEOUT).build();
    private final SecureRandom random = new SecureRandom();

    /** Where the browser goes after the callback: a console page, or a short page of our own. */
    public sealed interface Outcome {
        record Redirect(URI location) implements Outcome {
        }

        record Page(boolean deployed, String message) implements Outcome {
        }
    }

    @Autowired
    public DeployAuthorization(FlowService flowService, FlowRepository flows, RunService runService,
                               KeycloakTokens keycloak, FlowTokens offlineTokens, RegisteredClients clients,
                               JwtDecoder jwtDecoder, JsonMapper json,
                               @Value("${datahub.flow.public-url:http://localhost:8084}") String publicUrl,
                               @Value("${datahub.flow.console-url:}") String consoleUrl,
                               @Value("${datahub.api.url:http://localhost:8081}") String apiBaseUrl) {
        this(flowService, flows, runService, keycloak, offlineTokens, clients, jwtDecoder, json, publicUrl,
                consoleUrl, apiBaseUrl, Clock.systemUTC());
    }

    DeployAuthorization(FlowService flowService, FlowRepository flows, RunService runService,
                        KeycloakTokens keycloak, FlowTokens offlineTokens, RegisteredClients clients,
                        JwtDecoder jwtDecoder, JsonMapper json, String publicUrl, String consoleUrl,
                        String apiBaseUrl, Clock clock) {
        this.flowService = flowService;
        this.flows = flows;
        this.runService = runService;
        this.keycloak = keycloak;
        this.offlineTokens = offlineTokens;
        this.clients = clients;
        this.jwtDecoder = jwtDecoder;
        this.json = json;
        this.publicUrl = trimSlash(publicUrl);
        this.consoleUrl = trimSlash(consoleUrl);
        this.apiBaseUrl = apiBaseUrl;
        this.clock = clock;
    }

    /**
     * Starts deploying {@code version} (default the current one) as the caller: checks that they may,
     * and that the version still compiles, and returns the Keycloak URL to send them to. The
     * deployment is written only when they come back.
     */
    public URI authorize(String externalId, Integer version, Boolean enabled) {
        Caller caller = flowService.requireWrite();
        StoredFlow flow = flowService.visible(caller, externalId);
        if (!caller.canWrite(flow.dataSetId())) {
            throw FlowProblems.datasetForbidden("Deploying this flow takes write access to dataset "
                    + flow.dataSetExternalId() + ".");
        }
        DeploymentPlan plan = runService.plan(flow, version);
        String tenantId = TenantContext.getTenantId();
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        DeployState state = new DeployState(tenantId, flow.id(), plan.version(),
                enabled == null || enabled, caller.subject(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
                clock.instant().plus(STATE_LIFETIME));
        String signed = state.sign(DeployState.key(keycloak.clientSecret()), json);
        return keycloak.authorizationUrl(redirectUri(),
                "openid offline_access " + clients.organizationScope(tenantId), signed);
    }

    /**
     * Completes a deployment: Keycloak sent the browser back with a code (or an error). Never
     * throws: every way it can end is a page the person sees.
     */
    public Outcome callback(String code, String signedState, String error) {
        DeployState state;
        try {
            state = DeployState.verify(signedState, DeployState.key(keycloak.clientSecret()), json,
                    clock.instant());
        } catch (DeployState.InvalidState e) {
            log.warn("A deploy callback came back with a state that does not hold: {}", e.getMessage());
            return failed(null, "invalid-state", e.getMessage());
        }
        return TenantContext.callWith(state.tenantId(), () -> complete(state, code, error));
    }

    private Outcome complete(DeployState state, String code, String error) {
        StoredFlow flow = flows.findById(state.flowId()).orElse(null);
        if (flow == null) {
            return failed(null, "flow-gone", "The flow was deleted while it was being deployed.");
        }
        if (error != null) {
            return failed(flow, "denied", "Keycloak did not authorise the deployment (" + error + ").");
        }
        if (code == null) {
            return failed(flow, "no-code", "Keycloak sent no authorisation code.");
        }

        KeycloakTokens.Tokens tokens;
        try {
            tokens = keycloak.exchangeCode(code, redirectUri());
        } catch (KeycloakTokens.Rejected e) {
            return failed(flow, "code-rejected", "Keycloak refused the authorisation code: " + e.getMessage());
        } catch (KeycloakTokens.Unavailable e) {
            return failed(flow, "keycloak-unavailable", "Keycloak could not be reached. Try again.");
        }
        if (tokens.refreshToken() == null) {
            return failed(flow, "offline-access-missing", "Keycloak issued no offline token. Whoever "
                    + "deploys a flow needs the offline_access realm role.");
        }

        String problem = check(state, flow, tokens.accessToken());
        if (problem != null) {
            // The grant is good for nothing here; end the session it opened.
            keycloak.revoke(tokens.refreshToken());
            return failed(flow, problem, describe(problem));
        }

        try {
            DeploymentPlan plan = runService.plan(flow, state.version());
            offlineTokens.store(state.tenantId(), flow.id(), tokens.refreshToken());
            runService.apply(plan, state.enabled(), Identity.deployer(state.initiator()), state.initiator());
        } catch (FlowProblems.ProblemException e) {
            // Written for whoever is deploying: the version is gone, or no longer schedules.
            return failed(flow, "deploy-failed", "The flow could not be deployed: " + e.problem().getDetail());
        } catch (InvalidFlowException e) {
            return failed(flow, "deploy-failed", "The flow no longer compiles: " + e.problems().stream()
                    .map(p -> p.pointer() + ": " + p.message()).collect(Collectors.joining("; ")));
        } catch (RuntimeException e) {
            // Anything else is the service's own failure, and its message may name hosts, tables or
            // keys: the log has it, the page (which no token guards) does not.
            log.error("Deploying flow {} failed after Keycloak authorised it", flow.externalId(), e);
            return failed(flow, "deploy-failed", "The deployment could not be saved. Try again, or ask "
                    + "an administrator to look in the flow service's log.");
        }
        log.info("Flow {} deployed at version {} as its deployer", flow.externalId(), state.version());
        return consoleUrl.isEmpty()
                ? new Outcome.Page(true, "Flow " + flow.externalId() + " is deployed. You can close this page.")
                : new Outcome.Redirect(URI.create(consoleUrl + "/flows/" + encode(flow.externalId())
                + "?deployed=1"));
    }

    /**
     * Why the token cannot be the deployment's identity, or null when it can: it must be the person
     * who started the deployment, in this tenant's organization, and able to write the flow's
     * dataset.
     */
    private String check(DeployState state, StoredFlow flow, String accessToken) {
        Jwt jwt;
        try {
            // The service's own decoder: signature, issuer, expiry, and the single organization claim
            // the api demands. (It sets TenantContext from the token; ours is restored below.)
            jwt = jwtDecoder.decode(accessToken);
        } catch (JwtException e) {
            return "token-invalid";
        } finally {
            TenantContext.setTenantId(state.tenantId());
        }
        if (!state.initiator().equals(jwt.getSubject())) {
            log.warn("A deploy of flow {} came back signed in as someone other than who started it",
                    flow.externalId());
            return "wrong-user";
        }
        if (!state.tenantId().equals(organizationId(jwt))) {
            return "wrong-organization";
        }
        try {
            MeDataSets writable = DatahubClient.create(DatahubConfig.builder()
                    .baseUrl(apiBaseUrl).token(accessToken).requestTimeout(API_TIMEOUT).build(), http)
                    .me().datasets(Access.WRITE);
            if (!writable.all() && !writable.ids().contains(flow.dataSetId())) {
                return "dataset-forbidden";
            }
        } catch (DatahubApiException e) {
            log.warn("Could not check the deployer's access to dataset {}: {}", flow.dataSetId(),
                    e.getMessage());
            return "api-unavailable";
        }
        return null;
    }

    /** The id inside the token's single {@code organization} claim, as OrganizationValidator reads it. */
    private static String organizationId(Jwt jwt) {
        Object claim = jwt.getClaims().get("organization");
        if (claim instanceof Map<?, ?> orgs && orgs.size() == 1
                && orgs.values().iterator().next() instanceof Map<?, ?> details
                && details.get("id") instanceof String id) {
            return id;
        }
        return null;
    }

    private static String describe(String problem) {
        return switch (problem) {
            case "wrong-user" -> "You signed in to Keycloak as someone other than who started the "
                    + "deployment. Start it again, signed in as yourself.";
            case "wrong-organization" -> "Keycloak issued a token for another organization.";
            case "dataset-forbidden" -> "You may not write the flow's dataset, so its runs could not "
                    + "act as you.";
            case "api-unavailable" -> "Your access could not be checked: datahub-api did not answer. "
                    + "Try again.";
            default -> "Keycloak issued a token this service cannot use.";
        };
    }

    private Outcome failed(StoredFlow flow, String code, String message) {
        if (consoleUrl.isEmpty()) {
            return new Outcome.Page(false, message);
        }
        String path = flow == null ? "/flows" : "/flows/" + encode(flow.externalId());
        return new Outcome.Redirect(URI.create(consoleUrl + path + "?deployError=" + encode(code)));
    }

    String redirectUri() {
        return publicUrl + CALLBACK_PATH;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url == null ? "" : url;
    }
}
