// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.controllers.errors.IngestQuotaExceededException;
import ai.intellistream.datahub.api.controllers.errors.TenantLimitReachedException;
import ai.intellistream.datahub.api.services.IngestQuotaService.QuotaMetric;
import ai.intellistream.datahub.services.ValkeyService;
import ai.intellistream.datahub.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two ceilings and how they differ: a daily allowance that comes back, and a lifetime one that
 * does not. Both are counted locally and flushed, so the checks have to see uncommitted local
 * spending as well as the flushed total.
 */
class IngestQuotaServiceTest {

    private static final String TENANT = "acme";

    private final TenantLimitsService tenantLimits = mock(TenantLimitsService.class);
    private final ValkeyService valkeyService = mock(ValkeyService.class);
    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-08-25T12:00:00Z"));

    private IngestQuotaService service;

    private static TenantLimits limits(long eventsPerDay, long maxEventsTotal) {
        return new TenantLimits(0, 0, 0, 0,
                eventsPerDay, 0, 0, 0, 0,
                0, maxEventsTotal, 0, 0,
                0, 0);
    }

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT);
        service = new IngestQuotaService(tenantLimits, valkeyService, now::get);
        when(tenantLimits.forTenant(anyString())).thenReturn(limits(100, 0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void underTheDailyAllowanceIsAccepted() {
        assertThatCode(() -> service.checkAndRecord(QuotaMetric.EVENTS, 50)).doesNotThrowAnyException();
    }

    @Test
    void spendingIsCumulativeWithinTheWindow() {
        // Both calls are under the limit alone; together they are over it. Nothing has flushed yet,
        // so this only passes if a check counts what this instance is still holding.
        service.checkAndRecord(QuotaMetric.EVENTS, 60);

        assertThatThrownBy(() -> service.checkAndRecord(QuotaMetric.EVENTS, 60))
                .isInstanceOf(IngestQuotaExceededException.class);
    }

    @Test
    void aRefusedBatchIsNotCharged() {
        service.checkAndRecord(QuotaMetric.EVENTS, 60);

        assertThatThrownBy(() -> service.checkAndRecord(QuotaMetric.EVENTS, 60))
                .isInstanceOf(IngestQuotaExceededException.class);

        // The refused 60 must not have been added, or the tenant would be punished twice for it.
        assertThatCode(() -> service.checkAndRecord(QuotaMetric.EVENTS, 40)).doesNotThrowAnyException();
    }

    @Test
    void theQuotaRefusalPointsAtTheNextUtcMidnight() {
        service.checkAndRecord(QuotaMetric.EVENTS, 100);

        assertThatThrownBy(() -> service.checkAndRecord(QuotaMetric.EVENTS, 1))
                .isInstanceOfSatisfying(IngestQuotaExceededException.class, e -> {
                    // 12:00Z, so twelve hours of it.
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(12 * 3600);
                    assertThat(e.getLimit()).isEqualTo(100);
                });
    }

    @Test
    void aNewDayIsANewAllowance() {
        service.checkAndRecord(QuotaMetric.EVENTS, 100);
        assertThatThrownBy(() -> service.checkAndRecord(QuotaMetric.EVENTS, 1))
                .isInstanceOf(IngestQuotaExceededException.class);

        now.set(Instant.parse("2026-08-26T00:30:00Z"));

        // A different day means a different key, so the tenant starts over rather than staying
        // refused until someone intervenes.
        assertThatCode(() -> service.checkAndRecord(QuotaMetric.EVENTS, 100)).doesNotThrowAnyException();
    }

    @Test
    void theLifetimeCeilingIsSeparateAndNotRetryable() {
        when(tenantLimits.forTenant(anyString())).thenReturn(limits(1_000_000, 25));

        assertThatThrownBy(() -> service.checkAndRecord(QuotaMetric.EVENTS, 26))
                .isInstanceOf(TenantLimitReachedException.class)
                .hasMessageContaining("Contact IntelliStream");
    }

    @Test
    void aNewDayDoesNotResetTheLifetimeCeiling() {
        when(tenantLimits.forTenant(anyString())).thenReturn(limits(1_000_000, 25));
        service.checkAndRecord(QuotaMetric.EVENTS, 25);

        now.set(Instant.parse("2026-09-01T00:00:00Z"));

        // The whole point of a lifetime ceiling: waiting does not clear it.
        assertThatThrownBy(() -> service.checkAndRecord(QuotaMetric.EVENTS, 1))
                .isInstanceOf(TenantLimitReachedException.class);
    }

    @Test
    void anUnlimitedMetricIsNotCounted() {
        when(tenantLimits.forTenant(anyString())).thenReturn(limits(0, 0));

        service.checkAndRecord(QuotaMetric.EVENTS, 1_000_000);
        service.flush();

        verify(valkeyService, never()).incrementAndExpireIfNew(anyString(), anyLong(), anyLong());
    }

    @Test
    void flushPushesTheDailyCounterWithAnExpiryAndTheLifetimeOneWithout() {
        when(tenantLimits.forTenant(anyString())).thenReturn(limits(1000, 5000));
        service.checkAndRecord(QuotaMetric.EVENTS, 7);

        service.flush();

        // A daily key must expire or the window never rolls; a lifetime total must not.
        verify(valkeyService).incrementAndExpireIfNew(contains(":events:" + TENANT + ":2026"), anyLong(), anyLong());
        verify(valkeyService).increment(contains("dh:quota:total:events:" + TENANT), anyLong());
    }

    @Test
    void aFailedFlushKeepsTheDeltaForTheNextAttempt() {
        when(valkeyService.incrementAndExpireIfNew(anyString(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("valkey down"));
        service.checkAndRecord(QuotaMetric.EVENTS, 9);

        service.flush();
        service.flush();

        // Two attempts for one delta: losing it would let a tenant re-spend what it already used.
        verify(valkeyService, atLeastOnce()).incrementAndExpireIfNew(anyString(), anyLong(), anyLong());
    }

    @Test
    void anUnreadableCounterDoesNotRefuseIngest() {
        when(valkeyService.getString(anyString())).thenThrow(new IllegalStateException("valkey down"));

        assertThatCode(() -> service.checkAndRecord(QuotaMetric.EVENTS, 1)).doesNotThrowAnyException();
    }

    @Test
    void requestsWithNoTenantAreNotCharged() {
        TenantContext.clear();

        service.checkAndRecord(QuotaMetric.EVENTS, 1_000_000);
        service.flush();

        verify(valkeyService, never()).incrementAndExpireIfNew(anyString(), anyLong(), anyLong());
    }
}
