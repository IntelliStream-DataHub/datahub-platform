// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cache and failure-mode behaviour of {@link OrgGroupResolver}: which tier answers, how long a
 * revoked grant survives, and what happens when the identity provider is unreachable.
 *
 * <p>Time is injected rather than slept on, so the TTL boundaries are exercised exactly.
 */
class OrgGroupResolverTest {

    private static final String TENANT = "tenant-acme";
    private static final String OTHER_TENANT = "tenant-beta";
    private static final String SUBJECT = "user-1";
    private static final String KEY = "acl:groups:" + TENANT + ":" + SUBJECT;

    // The production defaults, so these tests drift if application.yml does.
    private static final Duration L1_TTL = Duration.ofSeconds(10);
    private static final Duration SOFT_TTL = Duration.ofSeconds(45);
    private static final Duration STALE_TTL = Duration.ofSeconds(90);

    private final KeycloakUserInfoClient userInfoClient = mock(KeycloakUserInfoClient.class);
    private final ValkeyService valkeyService = mock(ValkeyService.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AtomicLong now = new AtomicLong(1_000_000L);

    /** A tiny in-memory stand-in for Valkey, so L2 behaves like a real shared tier. */
    private final Map<String, String> valkeyStore = new HashMap<>();

    private OrgGroupResolver resolver;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT);
        when(valkeyService.getString(anyString()))
                .thenAnswer(inv -> valkeyStore.get(inv.getArgument(0, String.class)));
        doAnswer(inv -> {
            valkeyStore.put(inv.getArgument(0, String.class), inv.getArgument(1, String.class));
            return null;
        }).when(valkeyService).setString(anyString(), anyString(), anyLong());
        resolver = new OrgGroupResolver(userInfoClient, valkeyService, jsonMapper,
                L1_TTL, SOFT_TTL, STALE_TTL, now::get);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void advance(Duration d) {
        now.addAndGet(d.toMillis());
    }

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue("raw-token-value")
                .header("alg", "RS256")
                .subject(subject)
                .claim("sub", subject)
                .issuedAt(Instant.ofEpochSecond(1))
                .expiresAt(Instant.ofEpochSecond(9999999))
                .build();
    }

    private void userInfoReturns(Map<String, List<String>> byOrgId) {
        when(userInfoClient.fetchGroupsByOrganizationId(anyString())).thenReturn(byOrgId);
    }

    // ---- happy path and tiers ----------------------------------------------------------------

    @Test
    void fetchesFromUserInfoOnFirstCallAndPopulatesBothTiers() {
        userInfoReturns(Map.of(TENANT, List.of("/datasets/data_set_sap/read")));

        assertThat(resolver.groupsFor(jwt(SUBJECT))).containsExactly("/datasets/data_set_sap/read");

        verify(userInfoClient).fetchGroupsByOrganizationId("raw-token-value");
        // Written with the HARD ttl so it outlives its own freshness and can be served stale.
        verify(valkeyService).setString(eq(KEY), anyString(), eq(STALE_TTL.toSeconds()));
        assertThat(valkeyStore).containsKey(KEY);
    }

    @Test
    void servesFromL1WithoutTouchingValkeyOrUserInfoWithinL1Ttl() {
        userInfoReturns(Map.of(TENANT, List.of("/datasets/a/read")));
        resolver.groupsFor(jwt(SUBJECT));
        clearInvocations(userInfoClient, valkeyService);

        advance(Duration.ofSeconds(9));
        assertThat(resolver.groupsFor(jwt(SUBJECT))).containsExactly("/datasets/a/read");

        verifyNoInteractions(userInfoClient);
        verify(valkeyService, never()).getString(anyString());
    }

    @Test
    void fallsBackToValkeyWhenL1ExpiredButEntryIsStillFresh() {
        userInfoReturns(Map.of(TENANT, List.of("/datasets/a/read")));
        resolver.groupsFor(jwt(SUBJECT));
        clearInvocations(userInfoClient);

        // Past L1 (10s) but inside the soft TTL (60s): Valkey answers, Keycloak is not called.
        advance(Duration.ofSeconds(30));
        assertThat(resolver.groupsFor(jwt(SUBJECT))).containsExactly("/datasets/a/read");

        verifyNoInteractions(userInfoClient);
    }

    @Test
    void refetchesOnceTheEntryIsPastTheSoftTtl() {
        userInfoReturns(Map.of(TENANT, List.of("/datasets/a/read")));
        resolver.groupsFor(jwt(SUBJECT));

        advance(Duration.ofSeconds(50));
        userInfoReturns(Map.of(TENANT, List.of("/datasets/b/read")));
        assertThat(resolver.groupsFor(jwt(SUBJECT))).containsExactly("/datasets/b/read");

        verify(userInfoClient, times(2)).fetchGroupsByOrganizationId(anyString());
    }

    // ---- multi-organization ------------------------------------------------------------------

    /**
     * A caller in several organizations must get the groups of the organization matching
     * TenantContext, never simply the first entry in the claim.
     */
    @Test
    void selectsTheOrganizationMatchingTheCurrentTenant() {
        Map<String, List<String>> claim = new LinkedHashMap<>();
        claim.put(OTHER_TENANT, List.of("/datasets/beta_secret/read"));
        claim.put(TENANT, List.of("/datasets/acme_ok/read"));
        userInfoReturns(claim);

        assertThat(resolver.groupsFor(jwt(SUBJECT))).containsExactly("/datasets/acme_ok/read");
    }

    @Test
    void returnsNoGroupsWhenTheCurrentTenantIsAbsentFromTheClaim() {
        userInfoReturns(Map.of(OTHER_TENANT, List.of("/datasets/beta_secret/read")));

        assertThat(resolver.groupsFor(jwt(SUBJECT))).isEmpty();
    }

    // ---- failure modes -----------------------------------------------------------------------

    @Test
    void servesStaleWhenUserInfoIsDownAndTheEntryIsInsideTheStaleWindow() {
        userInfoReturns(Map.of(TENANT, List.of("/datasets/a/read")));
        resolver.groupsFor(jwt(SUBJECT));

        // Past the soft TTL (45s) so a refresh is attempted, but inside the stale window (90s).
        advance(Duration.ofSeconds(60));
        when(userInfoClient.fetchGroupsByOrganizationId(anyString()))
                .thenThrow(new UserInfoUnavailableException("connection refused"));

        assertThat(resolver.groupsFor(jwt(SUBJECT))).containsExactly("/datasets/a/read");
    }

    @Test
    void failsClosedWhenUserInfoIsDownAndTheEntryIsPastTheStaleWindow() {
        userInfoReturns(Map.of(TENANT, List.of("/datasets/a/read")));
        resolver.groupsFor(jwt(SUBJECT));

        advance(Duration.ofSeconds(120));
        when(userInfoClient.fetchGroupsByOrganizationId(anyString()))
                .thenThrow(new UserInfoUnavailableException("connection refused"));

        assertThatThrownBy(() -> resolver.groupsFor(jwt(SUBJECT)))
                .isInstanceOf(UserInfoUnavailableException.class);
    }

    /**
     * The stale window exists to ride out an outage. A refused token is not an outage — it is
     * UserInfo answering — so it must propagate immediately rather than letting an ended session
     * keep working for the rest of the window and then flip to 401 for no visible reason.
     */
    @Test
    void doesNotServeStaleWhenUserInfoRefusesTheToken() {
        userInfoReturns(Map.of(TENANT, List.of("/datasets/a/read")));
        resolver.groupsFor(jwt(SUBJECT));

        // Same instant the outage case above would happily serve the cached answer.
        advance(Duration.ofSeconds(60));
        when(userInfoClient.fetchGroupsByOrganizationId(anyString()))
                .thenThrow(new UserInfoRejectedException("UserInfo rejected the access token with HTTP 401", 401));

        assertThatThrownBy(() -> resolver.groupsFor(jwt(SUBJECT)))
                .isInstanceOf(UserInfoRejectedException.class);
    }

    @Test
    void failsClosedWhenUserInfoIsDownAndNothingWasEverCached() {
        when(userInfoClient.fetchGroupsByOrganizationId(anyString()))
                .thenThrow(new UserInfoUnavailableException("connection refused"));

        assertThatThrownBy(() -> resolver.groupsFor(jwt(SUBJECT)))
                .isInstanceOf(UserInfoUnavailableException.class);
    }

    /** A Valkey outage must degrade to a live fetch, never to a denial. */
    @Test
    void survivesAValkeyFailure() {
        when(valkeyService.getString(anyString())).thenThrow(new RuntimeException("valkey down"));
        doThrow(new RuntimeException("valkey down"))
                .when(valkeyService).setString(anyString(), anyString(), anyLong());
        userInfoReturns(Map.of(TENANT, List.of("/datasets/a/read")));

        assertThat(resolver.groupsFor(jwt(SUBJECT))).containsExactly("/datasets/a/read");
    }

    // ---- contract ----------------------------------------------------------------------------

    @Test
    void requiresATenantInContext() {
        TenantContext.clear();
        userInfoReturns(Map.of(TENANT, List.of("/datasets/a/read")));

        assertThatThrownBy(() -> resolver.groupsFor(jwt(SUBJECT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TenantContext");
    }

    /**
     * A token with no {@code sub} must fail, not resolve to "no groups". The two are
     * indistinguishable downstream — both deny every dataset check — but only one of them is the
     * caller's actual grant set. A Keycloak client missing the built-in {@code basic} scope mints
     * exactly this token, and the silent version of this branch turned that into a tenant-wide
     * lockout with nothing in the logs.
     */
    @Test
    void requiresASubjectInTheToken() {
        userInfoReturns(Map.of(TENANT, List.of("/datasets/a/read")));

        assertThatThrownBy(() -> resolver.groupsFor(jwt(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sub");
        verifyNoInteractions(userInfoClient);
    }

    @Test
    void returnsEmptyForANullJwt() {
        assertThat(resolver.groupsFor(null)).isEmpty();
        verifyNoInteractions(userInfoClient);
    }

    // ---- single-flight -----------------------------------------------------------------------

    /**
     * A cache expiry under load must send one request to Keycloak, not one per thread. The stub
     * blocks until every caller has arrived, so any per-thread fetching would deadlock the latch
     * rather than quietly pass.
     */
    @Test
    void concurrentCallersShareOneFetch() throws Exception {
        int threads = 8;
        AtomicInteger fetches = new AtomicInteger();
        CountDownLatch allArrived = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        when(userInfoClient.fetchGroupsByOrganizationId(anyString())).thenAnswer(inv -> {
            fetches.incrementAndGet();
            // Hold the single fetch open long enough for the other callers to pile onto it.
            allArrived.await(5, TimeUnit.SECONDS);
            return Map.of(TENANT, List.of("/datasets/a/read"));
        });

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    TenantContext.setTenantId(TENANT);
                    resolver.groupsFor(jwt(SUBJECT));
                } finally {
                    TenantContext.clear();
                    done.countDown();
                }
            });
        }

        // Give the threads a moment to converge on the same in-flight key, then release.
        Thread.sleep(200);
        allArrived.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(fetches.get()).isEqualTo(1);
    }
}
