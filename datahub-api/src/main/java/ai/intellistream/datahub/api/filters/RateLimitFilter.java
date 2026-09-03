// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.filters;

import ai.intellistream.datahub.api.config.LimitsProperties;
import ai.intellistream.datahub.api.services.TenantLimits;
import ai.intellistream.datahub.api.services.TenantLimitsService;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;

/**
 * Caps how many requests one tenant, and one user inside it, may make per minute.
 *
 * <p>Sits after authentication and before tenant provisioning, so an abusive caller is turned away
 * before the request costs a Vault lookup or a Flyway check, and so {@code /mcp/*} is covered by the
 * same rule as REST — the tools reach the services directly, but they arrive through this chain.
 *
 * <p>The window is a fixed minute rather than a token bucket. One atomic increment per request is
 * correct across instances with no shared state to reconcile, where a bucket would need a
 * read-modify-write of stored state per call to buy a smoothness that abuse protection does not
 * need: the worst case here is a caller who spends two windows' allowance across a window boundary,
 * which is still bounded.
 *
 * <p>A Valkey failure lets the request through. A limiter that cannot count is not a reason to
 * refuse traffic that is otherwise legitimate, and the ceilings below it (body size, batch size,
 * per-entity caps) still apply.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    /** Long enough that a window's key outlives the window even with clock skew between instances. */
    private static final long WINDOW_KEY_TTL_SECONDS = 120;

    private final LimitsProperties limits;
    private final TenantLimitsService tenantLimits;
    private final ValkeyService valkeyService;

    public RateLimitFilter(LimitsProperties limits,
                           TenantLimitsService tenantLimits,
                           ValkeyService valkeyService) {
        this.limits = limits;
        this.tenantLimits = tenantLimits;
        this.valkeyService = valkeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (!limits.getRate().isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            // A permit-all endpoint (session, swagger, the live-tail handshake). Those are bounded
            // per IP at the edge; there is no identity here to charge a request to.
            chain.doFilter(request, response);
            return;
        }

        TenantLimits effective = tenantLimits.forTenant(tenantId);
        boolean write = isWrite(request);
        long epochMinute = Instant.now().getEpochSecond() / 60;

        int tenantLimit = write ? effective.writePerMinutePerTenant() : effective.readPerMinutePerTenant();
        int userLimit = write ? effective.writePerMinutePerUser() : effective.readPerMinutePerUser();
        String kind = write ? "w" : "r";

        try {
            if (over(tenantLimit, "dh:rl:t:%s:%s:%d".formatted(tenantId, kind, epochMinute))) {
                refuse(request, response, tenantLimit, "tenant");
                return;
            }
            String subject = currentSubject();
            if (subject != null
                    && over(userLimit, "dh:rl:u:%s:%s:%d".formatted(subject, kind, epochMinute))) {
                refuse(request, response, userLimit, "user");
                return;
            }
        } catch (RuntimeException e) {
            log.warn("Rate limiting unavailable ({}); allowing the request through.", e.toString());
        }

        chain.doFilter(request, response);
    }

    /** Counts this request, and reports whether it has taken the caller past {@code limit}. */
    private boolean over(int limit, String key) {
        if (TenantLimits.unlimited(limit)) {
            return false;
        }
        return valkeyService.incrementAndExpireIfNew(key, 1, WINDOW_KEY_TTL_SECONDS) > limit;
    }

    /**
     * The last path segment of the endpoints that POST only because they carry a filter body. They
     * read, so they belong on the read budget: charging a console user's browsing to the smaller
     * write allowance would throttle looking at data long before anyone wrote any.
     */
    private static final Set<String> READ_SHAPED_POST_SEGMENTS = Set.of(
            "filter", "search", "byids", "list", "count", "check",
            "fetch-related", "fetch-nearest", "aggregate", "latest");

    private static boolean isWrite(HttpServletRequest request) {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return false;
        }
        if ("POST".equalsIgnoreCase(method) && isReadShaped(request)) {
            return false;
        }
        return true;
    }

    private static boolean isReadShaped(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        int lastSlash = uri.lastIndexOf('/');
        String segment = lastSlash < 0 ? uri : uri.substring(lastSlash + 1);
        return READ_SHAPED_POST_SEGMENTS.contains(segment.toLowerCase());
    }

    /** The JWT {@code sub}, or null when there is no authenticated principal. */
    private static String currentSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return authentication.getName();
    }

    /**
     * Written straight to the response: a filter runs outside the {@code @RestControllerAdvice}
     * chain, so nothing downstream would shape this body.
     */
    private static void refuse(HttpServletRequest request, HttpServletResponse response, int limit, String scope)
            throws IOException {
        long retryAfter = 60 - (Instant.now().getEpochSecond() % 60);
        log.info("Rate limit reached ({} scope, {}/min) on {} {}", scope, limit,
                request.getMethod(), request.getRequestURI());

        if (response.isCommitted()) {
            return;
        }
        response.reset();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {"type":"https://intellistream.ai/errors/rate-limit-exceeded",\
                "title":"Too many requests",\
                "status":429,\
                "detail":"This %s has used its %d requests per minute. Retry in %d seconds.",\
                "scope":"%s",\
                "limit":%d,\
                "retryAfter":%d}"""
                .formatted(scope, limit, retryAfter, scope, limit, retryAfter));
        response.getWriter().flush();
    }
}
