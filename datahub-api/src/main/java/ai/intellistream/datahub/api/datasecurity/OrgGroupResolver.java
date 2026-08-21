// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;

/**
 * The caller's Keycloak organization group paths for the current tenant, cached.
 *
 * <p>These paths are the dataset grants ({@code /datasets/<externalId>/read}, or the all-datasets
 * wildcard {@code /datasets/*&#47;read}). Keycloak, or the customer's own directory federated into
 * it, stays the only place access is administered; DataHub only reads. See
 * {@code datahub-api/KEYCLOAK_ORG_GROUPS.md}.
 *
 * <h2>Two cache tiers</h2>
 * <ul>
 *   <li><strong>L1, in-process</strong> ({@code l1-ttl}, default 10s). Takes Valkey off the hot
 *       path entirely for the common case of one user making a burst of requests.</li>
 *   <li><strong>L2, Valkey</strong> ({@code soft-ttl}, default 45s). Shared across api instances
 *       and survives restarts. This is what bounds revocation latency.</li>
 * </ul>
 * A permission change in Keycloak therefore takes effect within roughly {@code l1-ttl + soft-ttl}
 * in the worst case, since L1 cannot see an L2 write by another instance.
 *
 * <h2>Soft and hard TTL</h2>
 * The two TTLs answer different questions. {@code soft-ttl} is <em>when to refresh</em>, and bounds
 * how long a revoked grant survives normally. {@code stale-ttl} (default 90s) is <em>how long a
 * failed refresh may keep serving the old answer</em>, and is the fail-closed deadline.
 *
 * <p>Past {@code soft-ttl} a refresh is attempted. If it fails, the stale entry is still served
 * until {@code stale-ttl}, after which the request fails closed with
 * {@link UserInfoUnavailableException}. One TTL could not express both: failing closed at expiry
 * would make Keycloak a hard dependency of every request whose entry had just lapsed, and serving
 * stale indefinitely would keep revoked grants alive for the length of an outage. Two lets a brief
 * blip pass unnoticed while still converging to denial.
 *
 * <p>With these defaults the stale window is deliberately narrow: an identity-provider outage
 * lasting more than ~90s past an entry's last successful fetch starts denying requests rather than
 * trusting an increasingly old answer.
 *
 * <h2>Single-flight</h2>
 * Concurrent callers for the same key share one in-flight fetch, so an expiry under load sends one
 * request to Keycloak rather than one per request thread.
 */
@Component
@Slf4j
public class OrgGroupResolver {

    private static final String KEY_PREFIX = "acl:groups:";

    /**
     * Bound on L1 size. Entries are tiny and expire in seconds, so this only matters for a tenant
     * with a very large number of distinct active principals.
     */
    private static final int L1_MAX_ENTRIES = 10_000;

    private final KeycloakUserInfoClient userInfoClient;
    private final ValkeyService valkeyService;
    private final JsonMapper jsonMapper;
    private final LongSupplier clock;

    private final long l1TtlMillis;
    private final long softTtlMillis;
    private final long staleTtlMillis;

    private final ConcurrentMap<String, Entry> l1 = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Entry>> inFlight = new ConcurrentHashMap<>();

    /** A resolved group list plus when it was fetched, so staleness is judged from the entry itself. */
    record Entry(List<String> groups, long fetchedAtMillis) {}

    // Explicit: the package-private test constructor below means there is more than one, and
    // Spring only auto-selects when a class has exactly one.
    @Autowired
    public OrgGroupResolver(
            KeycloakUserInfoClient userInfoClient,
            ValkeyService valkeyService,
            JsonMapper jsonMapper,
            @Value("${datahub.acl.groups.l1-ttl:10s}") Duration l1Ttl,
            @Value("${datahub.acl.groups.soft-ttl:45s}") Duration softTtl,
            @Value("${datahub.acl.groups.stale-ttl:90s}") Duration staleTtl) {
        this(userInfoClient, valkeyService, jsonMapper, l1Ttl, softTtl, staleTtl, System::currentTimeMillis);
    }

    /** Test constructor: a controllable clock so TTL behaviour can be exercised without sleeping. */
    OrgGroupResolver(
            KeycloakUserInfoClient userInfoClient,
            ValkeyService valkeyService,
            JsonMapper jsonMapper,
            Duration l1Ttl,
            Duration softTtl,
            Duration staleTtl,
            LongSupplier clock) {
        this.userInfoClient = userInfoClient;
        this.valkeyService = valkeyService;
        this.jsonMapper = jsonMapper;
        this.l1TtlMillis = l1Ttl.toMillis();
        this.softTtlMillis = softTtl.toMillis();
        this.staleTtlMillis = staleTtl.toMillis();
        this.clock = clock;
    }

    /**
     * Group paths for the authenticated caller in the current tenant, for use on a request thread.
     *
     * <p>Returns an empty list when there is no JWT principal, when the caller holds no groups, or
     * when the current tenant is absent from the caller's {@code organization} claim.
     *
     * @throws UserInfoUnavailableException if the groups cannot be determined and no cached answer
     *                                      is recent enough to serve
     */
    public List<String> groupsForCurrentCaller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            return List.of();
        }
        return groupsFor(jwtAuth.getToken());
    }

    /**
     * Group paths for the principal behind an explicit JWT. For callers off the request thread —
     * the WebSocket handlers authenticate a token by hand and have no {@code SecurityContext}.
     */
    public List<String> groupsFor(Jwt jwt) {
        if (jwt == null) {
            return List.of();
        }
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("No tenant in TenantContext; cannot resolve organization groups");
        }
        String subject = jwt.getSubject();
        if (subject == null) {
            throw new IllegalStateException(
                    "No 'sub' claim in the access token; cannot resolve organization groups. "
                            + "An OAuth 2.0 JWT access token must carry one (RFC 9068). On Keycloak "
                            + "25+, add the built-in 'basic' client scope to the client's default "
                            + "client scopes.");
        }
        return resolve(tenantId, subject, jwt.getTokenValue());
    }

    private List<String> resolve(String tenantId, String subject, String bearerToken) {
        String key = KEY_PREFIX + tenantId + ":" + subject;
        long now = clock.getAsLong();

        Entry l1Entry = l1.get(key);
        if (l1Entry != null && age(l1Entry, now) <= l1TtlMillis) {
            return l1Entry.groups();
        }

        Entry l2Entry = readFromValkey(key);
        if (l2Entry != null && age(l2Entry, now) <= softTtlMillis) {
            l1.put(key, l2Entry);
            return l2Entry.groups();
        }

        // Best stale candidate to fall back on if the refresh fails. L2 is preferred when both
        // exist: another instance may have refreshed it more recently than this one's L1.
        Entry stale = newer(l1Entry, l2Entry);
        return refresh(key, tenantId, subject, bearerToken, stale).groups();
    }

    private Entry refresh(String key, String tenantId, String subject, String bearerToken, Entry stale) {
        CompletableFuture<Entry> mine = new CompletableFuture<>();
        CompletableFuture<Entry> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            // Another thread is already fetching this exact key; take its result rather than
            // sending a second identical request.
            try {
                return running.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UserInfoUnavailableException("Interrupted awaiting organization groups", e);
            } catch (ExecutionException e) {
                return serveStaleOrFail(stale, key, e.getCause());
            }
        }

        try {
            Map<String, List<String>> byOrgId = userInfoClient.fetchGroupsByOrganizationId(bearerToken);
            // Match on organization id, not "first entry wins": a caller can belong to several
            // organizations, and only the one matching TenantContext governs this request.
            List<String> groups = byOrgId.getOrDefault(tenantId, List.of());
            if (!byOrgId.containsKey(tenantId)) {
                log.debug("Tenant {} absent from UserInfo organization claim for subject {}; " +
                        "treating as no grants", tenantId, subject);
            }
            Entry fresh = new Entry(groups, clock.getAsLong());
            writeToValkey(key, fresh);
            l1.put(key, fresh);
            sweepL1IfLarge();
            mine.complete(fresh);
            return fresh;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            return serveStaleOrFail(stale, key, e);
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private Entry serveStaleOrFail(Entry stale, String key, Throwable cause) {
        if (stale != null && age(stale, clock.getAsLong()) <= staleTtlMillis) {
            log.warn("Serving stale organization groups for {} ({}s old): {}",
                    key, age(stale, clock.getAsLong()) / 1000, cause.getMessage());
            return stale;
        }
        if (cause instanceof UserInfoUnavailableException unavailable) {
            throw unavailable;
        }
        throw new UserInfoUnavailableException(
                "Could not resolve organization groups for " + key + ": " + cause.getMessage(), cause);
    }

    // ---- cache tiers -----------------------------------------------------------------------

    private Entry readFromValkey(String key) {
        try {
            String json = valkeyService.getString(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return jsonMapper.readValue(json, Entry.class);
        } catch (Exception e) {
            // A cache read failure must not deny the request — fall through to a live fetch.
            log.warn("Valkey read failed for {}: {}", key, e.getMessage());
            return null;
        }
    }

    private void writeToValkey(String key, Entry entry) {
        try {
            // Stored with the HARD ttl, not the soft one: the entry has to outlive its own
            // freshness so it is still there to serve stale during an outage. Freshness is judged
            // from the embedded timestamp, not from key expiry.
            valkeyService.setString(key, jsonMapper.writeValueAsString(entry), staleTtlMillis / 1000);
        } catch (Exception e) {
            log.warn("Valkey write failed for {}: {}", key, e.getMessage());
        }
    }

    private void sweepL1IfLarge() {
        if (l1.size() <= L1_MAX_ENTRIES) {
            return;
        }
        long now = clock.getAsLong();
        l1.values().removeIf(e -> now - e.fetchedAtMillis() > staleTtlMillis);
        if (l1.size() > L1_MAX_ENTRIES) {
            // Every entry is still within the stale window. Drop the tier rather than grow without
            // bound; the cost is refetching, and L2 absorbs most of it.
            log.info("Clearing L1 organization-group cache ({} entries over the {} cap)",
                    l1.size(), L1_MAX_ENTRIES);
            l1.clear();
        }
    }

    private long age(Entry entry, long now) {
        return now - entry.fetchedAtMillis();
    }

    private static Entry newer(Entry a, Entry b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.fetchedAtMillis() >= a.fetchedAtMillis() ? b : a;
    }
}
