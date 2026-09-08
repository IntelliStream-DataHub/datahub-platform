// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.services;

import ai.intellistream.datahub.api.config.LimitsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resolving a tenant's limits: overrides win, NULL inherits, and nothing about a missing or broken
 * row is allowed to refuse a request.
 */
class TenantLimitsServiceTest {

    private static final String TENANT = "acme";

    private final LimitsProperties defaults = new LimitsProperties();
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AtomicLong now = new AtomicLong(0);

    private TenantLimitsService service(Duration ttl) {
        return new TenantLimitsService(defaults, jdbcTemplate, ttl, now::get);
    }

    /**
     * A one-row result where only {@code set} columns have a value and everything else is NULL.
     * {@code wasNull()} has to answer for the column just read, which is what tells an override
     * apart from an inherited default.
     */
    @SuppressWarnings("unchecked")
    private void rowWith(Map<String, Number> set) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        AtomicBoolean lastWasNull = new AtomicBoolean(true);

        when(rs.next()).thenReturn(true);
        when(rs.getInt(anyString())).thenAnswer(inv -> {
            Number value = set.get(inv.<String>getArgument(0));
            lastWasNull.set(value == null);
            return value == null ? 0 : value.intValue();
        });
        when(rs.getLong(anyString())).thenAnswer(inv -> {
            Number value = set.get(inv.<String>getArgument(0));
            lastWasNull.set(value == null);
            return value == null ? 0L : value.longValue();
        });
        when(rs.wasNull()).thenAnswer(inv -> lastWasNull.get());

        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class)))
                .thenAnswer(inv -> ((ResultSetExtractor<TenantLimits>) inv.getArgument(1)).extractData(rs));
    }

    @Test
    void anOverrideWinsAndTheRestOfTheRowStillInherits() throws Exception {
        rowWith(Map.of("write_per_minute_per_tenant", 42, "max_resources", 1000L));

        TenantLimits limits = service(Duration.ofMinutes(5)).forTenant(TENANT);

        assertThat(limits.writePerMinutePerTenant()).isEqualTo(42);
        assertThat(limits.maxResources()).isEqualTo(1000L);
        assertThat(limits.readPerMinutePerTenant())
                .as("a NULL column inherits the deployment default")
                .isEqualTo(defaults.getRate().getReadPerMinutePerTenant());
    }

    @Test
    void aZeroOverrideMeansUnlimitedRatherThanInherit() throws Exception {
        // This is how a limit gets lifted for a customer who asked: set the column to 0.
        rowWith(Map.of("write_per_minute_per_tenant", 0));

        TenantLimits limits = service(Duration.ofMinutes(5)).forTenant(TENANT);

        assertThat(limits.writePerMinutePerTenant()).isZero();
        assertThat(TenantLimits.unlimited(limits.writePerMinutePerTenant())).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMissingRowInheritsEveryDefault() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class))).thenReturn(null);

        TenantLimits limits = service(Duration.ofMinutes(5)).forTenant(TENANT);

        assertThat(limits.writePerMinutePerTenant())
                .isEqualTo(defaults.getRate().getWritePerMinutePerTenant());
        assertThat(limits.readPerMinutePerUser())
                .isEqualTo(defaults.getRate().getReadPerMinutePerUser());
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnreadableTableFallsBackToDefaultsRatherThanFailing() {
        // The rate-limit filter runs before tenant provisioning, so a first-touch request reaches
        // this before the schema exists. That must not refuse the request.
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class)))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException(
                        "read", "SELECT * FROM tenant_limits WHERE id = 1", new java.sql.SQLException()));

        TenantLimits limits = service(Duration.ofMinutes(5)).forTenant(TENANT);

        assertThat(limits.writePerMinutePerTenant())
                .isEqualTo(defaults.getRate().getWritePerMinutePerTenant());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aResolvedRowIsCachedUntilTheTtlExpires() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class))).thenReturn(null);
        TenantLimitsService service = service(Duration.ofMinutes(5));

        service.forTenant(TENANT);
        now.addAndGet(Duration.ofMinutes(4).toMillis());
        service.forTenant(TENANT);

        verify(jdbcTemplate, times(1)).query(anyString(), any(ResultSetExtractor.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pastTheTtlTheRowIsReadAgainSoAnUpdatePropagates() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class))).thenReturn(null);
        TenantLimitsService service = service(Duration.ofMinutes(5));

        service.forTenant(TENANT);
        now.addAndGet(Duration.ofMinutes(5).toMillis() + 1);
        service.forTenant(TENANT);

        // This re-read is the whole propagation mechanism: raising a limit is an UPDATE, and every
        // instance picks it up within the TTL with no restart.
        verify(jdbcTemplate, times(2)).query(anyString(), any(ResultSetExtractor.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenantsAreCachedIndependently() {
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class))).thenReturn(null);
        TenantLimitsService service = service(Duration.ofMinutes(5));

        service.forTenant("tenant-a");
        service.forTenant("tenant-b");
        service.forTenant("tenant-a");

        verify(jdbcTemplate, times(2)).query(anyString(), any(ResultSetExtractor.class));
    }
}
