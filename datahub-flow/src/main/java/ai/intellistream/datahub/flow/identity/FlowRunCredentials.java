// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.identity;

import ai.intellistream.datahub.flow.flows.FlowRepository;
import ai.intellistream.datahub.flow.flows.FlowRepository.Identity;
import ai.intellistream.datahub.flow.flows.FlowRepository.IdentityState;
import ai.intellistream.datahub.flow.runs.RunCredentials;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tokens for a run, as the identity its flow is deployed with: the deployer, by refreshing the
 * offline token they granted when deploying, or a registered client, by its client credentials.
 *
 * <p>An access token is kept until shortly before it expires and shared by the runs of one flow, so
 * a flow running every minute does not ask Keycloak every minute. It is cached under the flow and
 * holds for the offline tokens it stands for: the one it was refreshed from and the one Keycloak
 * reissued with it, which is stored in its place. Deploying afresh stores a token it does not stand
 * for, so the next run starts from the new grant at once.
 *
 * <p>When Keycloak <em>refuses</em> — a revoked offline token, a disabled user, a client whose secret
 * was rotated without updating the settings — the deployment is marked as needing
 * re-authorisation, no more runs are scheduled for it, and the run fails without retrying: no
 * attempt can get past it. When Keycloak cannot be <em>reached</em>, the run fails and is retried
 * like any other failure.
 */
@Slf4j
@Component
public class FlowRunCredentials implements RunCredentials {

    /**
     * An access token is renewed when it has less than this left, or less than half its lifetime for
     * one that lives shorter: a realm issuing 60-second tokens would otherwise refresh on every request.
     */
    static final Duration MARGIN = Duration.ofSeconds(60);
    private static final int MAX_CACHED = 1_000;

    private final FlowRepository flows;
    private final FlowTokens offlineTokens;
    private final RegisteredClients clients;
    private final KeycloakTokens keycloak;
    private final LongSupplier nanoTime;

    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_CACHED;
        }
    };

    private record Cached(String accessToken, long renewAtNanos, Set<String> sources) {
    }

    @Autowired
    public FlowRunCredentials(FlowRepository flows, FlowTokens offlineTokens, RegisteredClients clients,
                              KeycloakTokens keycloak) {
        this(flows, offlineTokens, clients, keycloak, System::nanoTime);
    }

    FlowRunCredentials(FlowRepository flows, FlowTokens offlineTokens, RegisteredClients clients,
                       KeycloakTokens keycloak, LongSupplier nanoTime) {
        this.flows = flows;
        this.offlineTokens = offlineTokens;
        this.clients = clients;
        this.keycloak = keycloak;
        this.nanoTime = nanoTime;
    }

    @Override
    public Supplier<String> tokens(String tenantId, long flowId) {
        IdentityState state = flows.identity(flowId).orElseThrow(() ->
                new RunIdentityException("Flow " + flowId + " has no identity to run as; deploy it.", true));
        if (!state.usable()) {
            throw new RunIdentityException("The flow's deployment needs re-authorisation: "
                    + state.reason(), true);
        }
        Identity identity = state.identity();
        Supplier<KeycloakTokens.Tokens> fetch;
        String key;
        String source;
        if (Identity.DEPLOYER.equals(identity.kind())) {
            String offline = offlineTokens.get(tenantId, flowId);
            if (offline == null) {
                throw reject(tenantId, flowId, "No offline token is stored for the deployment.");
            }
            key = tenantId + "/" + flowId + "/deployer";
            source = digest(offline);
            fetch = () -> {
                KeycloakTokens.Tokens tokens = keycloak.refresh(offline);
                // Keycloak reissues the offline token on each refresh, and with refresh-token rotation
                // on the old one stops working: keep the new one.
                if (tokens.refreshToken() != null && !tokens.refreshToken().equals(offline)) {
                    TenantContext.runWith(tenantId, () ->
                            offlineTokens.store(tenantId, flowId, tokens.refreshToken()));
                }
                return tokens;
            };
        } else {
            key = tenantId + "/" + flowId + "/client";
            source = identity.clientName();
            fetch = () -> clients.tokens(tenantId, identity.clientName());
        }
        Supplier<String> supplier = () -> token(tenantId, flowId, key, source, fetch);
        // Taken once now, so that a refused identity fails the run before it starts rather than
        // inside a processor.
        supplier.get();
        return supplier;
    }

    private String token(String tenantId, long flowId, String key, String source,
                         Supplier<KeycloakTokens.Tokens> fetch) {
        synchronized (cache) {
            Cached cached = cache.get(key);
            if (usable(cached, source)) {
                return cached.accessToken();
            }
        }
        // One fetch per flow at a time, so concurrent runs of a flow share it.
        synchronized (key.intern()) {
            synchronized (cache) {
                Cached cached = cache.get(key);
                if (usable(cached, source)) {
                    return cached.accessToken();
                }
            }
            KeycloakTokens.Tokens tokens;
            try {
                tokens = fetch.get();
            } catch (KeycloakTokens.Rejected e) {
                throw reject(tenantId, flowId, e.getMessage());
            } catch (KeycloakTokens.Unavailable e) {
                throw new RunIdentityException("Keycloak could not be asked for the run's token: "
                        + e.getMessage());
            }
            // A client's tokens come with no refresh token; a deployer's may come back unchanged.
            Set<String> sources = tokens.refreshToken() == null ? Set.of(source)
                    : Stream.of(source, digest(tokens.refreshToken())).collect(Collectors.toUnmodifiableSet());
            long lifetime = tokens.expiresIn().toNanos();
            long renewAt = nanoTime.getAsLong() + lifetime - Math.min(MARGIN.toNanos(), lifetime / 2);
            synchronized (cache) {
                cache.put(key, new Cached(tokens.accessToken(), renewAt, sources));
            }
            return tokens.accessToken();
        }
    }

    private boolean usable(Cached cached, String source) {
        return cached != null && cached.sources().contains(source)
                && cached.renewAtNanos() - nanoTime.getAsLong() > 0;
    }

    /** Marks the deployment as needing re-authorisation; returns what fails the run for good. */
    private RunIdentityException reject(String tenantId, long flowId, String reason) {
        log.warn("Keycloak refused the identity of flow {} in tenant {}; it needs re-authorisation: {}",
                flowId, tenantId, reason);
        // The supplier is also called on the SDK's own threads, which carry no tenant.
        TenantContext.runWith(tenantId, () -> flows.needsReauthorisation(flowId, reason));
        return new RunIdentityException("Keycloak refused the flow's identity: " + reason, true);
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
