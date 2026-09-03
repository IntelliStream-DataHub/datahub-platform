// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.filters;

import ai.intellistream.datahub.api.config.LimitsProperties;
import ai.intellistream.datahub.api.services.TenantLimits;
import ai.intellistream.datahub.api.services.TenantLimitsService;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-minute budget, and the two ways it must not misfire: it has to charge the right identity,
 * and it has to let traffic through when it cannot count at all.
 */
class RateLimitFilterTest {

    private static final String TENANT = "2c5e2e73-2c2e-4516-ab58-4e602e1c495b";

    private final LimitsProperties limits = new LimitsProperties();
    private final TenantLimitsService tenantLimits = mock(TenantLimitsService.class);
    private final ValkeyService valkeyService = mock(ValkeyService.class);
    private final RateLimitFilter filter = new RateLimitFilter(limits, tenantLimits, valkeyService);

    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    /** Rate limits only; the quota and lifetime figures are unlimited here. */
    private static TenantLimits limitsOf(int writeTenant, int readTenant, int writeUser, int readUser) {
        return new TenantLimits(writeTenant, readTenant, writeUser, readUser,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT);
        when(tenantLimits.forTenant(anyString())).thenReturn(limitsOf(10, 20, 5, 8));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String subject) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(subject, "n/a", List.of()));
    }

    /** Every counter reports {@code count}, so one stub drives whichever key is checked first. */
    private void countsReach(long count) {
        when(valkeyService.incrementAndExpireIfNew(anyString(), anyLong(), anyLong())).thenReturn(count);
    }

    @Test
    void underTheBudgetPassesThrough() throws Exception {
        countsReach(3);

        filter.doFilter(new MockHttpServletRequest("POST", "/events/create"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void overTheTenantBudgetIsRefusedWithRetryAfter() throws Exception {
        countsReach(11);   // the tenant write budget is 10

        filter.doFilter(new MockHttpServletRequest("POST", "/events/create"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("rate-limit-exceeded", "\"scope\":\"tenant\"");
        assertThat(chain.getRequest()).as("the chain must not run").isNull();

        int retryAfter = Integer.parseInt(response.getHeader(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isBetween(1, 60);
    }

    @Test
    void readsAndWritesHaveSeparateBudgets() throws Exception {
        // 11 is over the write budget (10) but well under the read budget (20), so a GET survives
        // a count that would refuse a POST.
        countsReach(11);

        filter.doFilter(new MockHttpServletRequest("GET", "/events"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void aPostThatOnlyReadsIsChargedToTheReadBudget() throws Exception {
        // /events/filter, /resources/search and friends POST only because they carry a filter body.
        // Charging them to the write budget would throttle browsing long before anyone wrote data.
        countsReach(11);   // over the write budget (10), under the read one (20)

        filter.doFilter(new MockHttpServletRequest("POST", "/events/filter"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(valkeyService).incrementAndExpireIfNew(
                org.mockito.ArgumentMatchers.matches("dh:rl:t:" + TENANT + ":r:\\d+"), anyLong(), anyLong());
    }

    @Test
    void aRealWriteIsStillChargedToTheWriteBudget() throws Exception {
        countsReach(11);

        filter.doFilter(new MockHttpServletRequest("POST", "/events/create"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void aDeleteIsAWriteWhateverItsPathLooksLike() throws Exception {
        countsReach(11);

        filter.doFilter(new MockHttpServletRequest("DELETE", "/events/delete"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void theUserBudgetAppliesInsideTheTenantBudget() throws Exception {
        authenticateAs("user-1");
        countsReach(6);   // under the tenant write budget (10), over the user one (5)

        filter.doFilter(new MockHttpServletRequest("POST", "/events/create"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getContentAsString()).contains("\"scope\":\"user\"");
    }

    @Test
    void anUnlimitedBudgetIsNotEvenCounted() throws Exception {
        when(tenantLimits.forTenant(anyString())).thenReturn(limitsOf(0, 0, 0, 0));

        filter.doFilter(new MockHttpServletRequest("POST", "/events/create"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(valkeyService, never()).incrementAndExpireIfNew(anyString(), anyLong(), anyLong());
    }

    @Test
    void requestsWithNoTenantAreNotCharged() throws Exception {
        // A permit-all endpoint: swagger, the session routes, the live-tail handshake.
        TenantContext.clear();

        filter.doFilter(new MockHttpServletRequest("GET", "/swagger-ui/index.html"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(valkeyService, never()).incrementAndExpireIfNew(anyString(), anyLong(), anyLong());
    }

    @Test
    void aValkeyOutageLetsTrafficThrough() throws Exception {
        // Refusing everything because the counter is unavailable would turn a cache outage into a
        // full outage. The size and batch caps still apply underneath.
        when(valkeyService.incrementAndExpireIfNew(anyString(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("valkey down"));

        filter.doFilter(new MockHttpServletRequest("POST", "/events/create"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void disablingTheLimiterSkipsItEntirely() throws Exception {
        limits.getRate().setEnabled(false);

        filter.doFilter(new MockHttpServletRequest("POST", "/events/create"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(valkeyService, never()).incrementAndExpireIfNew(anyString(), anyLong(), anyLong());
    }

    @Test
    void theWindowKeyCarriesTenantMethodClassAndMinute() throws Exception {
        countsReach(1);

        filter.doFilter(new MockHttpServletRequest("POST", "/events/create"), response, chain);

        // Tenant-scoped, write-scoped, and per-minute: without the minute the window never rolls,
        // and without the tenant two customers would share one budget.
        verify(valkeyService).incrementAndExpireIfNew(
                org.mockito.ArgumentMatchers.matches("dh:rl:t:" + TENANT + ":w:\\d+"), anyLong(), anyLong());
    }
}
