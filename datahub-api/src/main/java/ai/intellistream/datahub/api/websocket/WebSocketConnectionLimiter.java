// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.websocket;

import ai.intellistream.datahub.api.config.LimitsProperties;
import ai.intellistream.datahub.api.services.TenantLimits;
import ai.intellistream.datahub.api.services.TenantLimitsService;
import ai.intellistream.datahub.services.ValkeyService;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Caps how many WebSocket connections one tenant, and one user inside it, may hold open at once.
 *
 * <p>A socket costs more than the request that opened it: a durable subscription keeps broker
 * resources reserved whether or not anyone is reading, so an idle hoard is as expensive as a busy
 * one, and datahub-cleanup only sweeps subscriptions nobody owns rather than ones somebody is
 * holding on purpose. The per-minute rate limit does not reach this at all — a handshake happens
 * once and the cost is what follows it.
 *
 * <p>Sockets live on whichever instance accepted them, so the count has to be shared. It is a Valkey
 * sorted set per scope, scored by last heartbeat: connecting adds a member, the handlers' existing
 * ping refreshes the score, closing removes it, and anything not refreshed inside
 * {@link #STALE_AFTER_SECONDS} is ignored and swept. That last part is what makes an instance dying
 * mid-connection self-correcting — its sockets age out instead of permanently occupying the budget.
 *
 * <p>A Valkey failure allows the connection. Refusing to open sockets because the registry is
 * unreachable would turn a cache outage into an outage of the live features.
 */
@Slf4j
@Component
public class WebSocketConnectionLimiter {

    /** Comfortably beyond the handlers' 15s ping and the 45s idle timeout. */
    private static final long STALE_AFTER_SECONDS = 60;

    private final TenantLimitsService tenantLimits;
    private final ValkeyService valkeyService;
    private final LimitsProperties limits;

    public WebSocketConnectionLimiter(TenantLimitsService tenantLimits,
                                      ValkeyService valkeyService,
                                      LimitsProperties limits) {
        this.tenantLimits = tenantLimits;
        this.valkeyService = valkeyService;
        this.limits = limits;
    }

    /** Why a connection was refused, so the handler can tell the caller something useful. */
    public record Refusal(String scope, long limit) {

        public String message() {
            return ("This %s already has %d open WebSocket connections, which is the limit. Close one, "
                    + "or contact IntelliStream to have the limit raised.").formatted(scope, limit);
        }
    }

    /**
     * Register a new connection, or explain why it cannot be accepted.
     *
     * @return empty when the connection may proceed
     */
    public Optional<Refusal> register(String tenantId, String subject, String sessionId) {
        if (tenantId == null || sessionId == null) {
            return Optional.empty();
        }
        TenantLimits effective = tenantLimits.forTenant(tenantId);
        if (effective == null) {
            return Optional.empty();
        }

        try {
            long tenantLimit = effective.maxWsSocketsPerTenant();
            if (!TenantLimits.unlimited(tenantLimit)
                    && valkeyService.countLiveMembers(tenantKey(tenantId), STALE_AFTER_SECONDS) >= tenantLimit) {
                return Optional.of(new Refusal("tenant", tenantLimit));
            }

            long userLimit = effective.maxWsSocketsPerUser();
            if (subject != null && !TenantLimits.unlimited(userLimit)
                    && valkeyService.countLiveMembers(userKey(tenantId, subject), STALE_AFTER_SECONDS) >= userLimit) {
                return Optional.of(new Refusal("user", userLimit));
            }

            heartbeat(tenantId, subject, sessionId);
        } catch (RuntimeException e) {
            log.warn("WebSocket connection limiting unavailable ({}); allowing the connection.", e.toString());
        }
        return Optional.empty();
    }

    /** Refresh this connection's score, so it keeps counting. Called from the handlers' ping. */
    public void heartbeat(String tenantId, String subject, String sessionId) {
        if (tenantId == null || sessionId == null) {
            return;
        }
        try {
            valkeyService.touchMember(tenantKey(tenantId), sessionId, STALE_AFTER_SECONDS);
            if (subject != null) {
                valkeyService.touchMember(userKey(tenantId, subject), sessionId, STALE_AFTER_SECONDS);
            }
        } catch (RuntimeException e) {
            log.debug("Could not refresh WebSocket registration for {}: {}", sessionId, e.toString());
        }
    }

    /** Free the slot. Best-effort: a missed release ages out on its own. */
    public void release(String tenantId, String subject, String sessionId) {
        if (tenantId == null || sessionId == null) {
            return;
        }
        try {
            valkeyService.removeMember(tenantKey(tenantId), sessionId);
            if (subject != null) {
                valkeyService.removeMember(userKey(tenantId, subject), sessionId);
            }
        } catch (RuntimeException e) {
            log.debug("Could not release WebSocket registration for {}: {}", sessionId, e.toString());
        }
    }

    /**
     * The per-socket subscription cap. Deployment-wide rather than per tenant: it bounds one
     * socket's fan-out, and an in-session counter is exact, so it needs no shared state.
     */
    public int maxSubscriptionsPerSocket() {
        return limits.getWebsocket().getMaxSubscriptionsPerSocket();
    }

    private static String tenantKey(String tenantId) {
        return "dh:ws:t:" + tenantId;
    }

    private static String userKey(String tenantId, String subject) {
        return "dh:ws:u:%s:%s".formatted(tenantId, subject);
    }
}
