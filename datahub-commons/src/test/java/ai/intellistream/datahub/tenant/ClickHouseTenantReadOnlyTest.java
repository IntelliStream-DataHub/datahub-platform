// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code clickhouse} block of a tenant's entry in {@code tenant-resources}.
 *
 * <p>The tenant manager writes these keys and this repository reads them, so the spelling is a
 * contract across two codebases — {@code readonly-user} and {@code readonly-password}, hyphenated
 * like every other key in the secret. Getting it wrong does not fail: Jackson leaves the fields
 * null, {@link ClickHouseTenant#hasReadOnlyUser()} says no, and every tenant silently falls back
 * to the owner. That is exactly the failure this pins.
 */
class ClickHouseTenantReadOnlyTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private ClickHouseTenant clickhouse(String body) {
        return json.readValue(body, Tenant.class).getClickHouseTenant();
    }

    @Test
    void readsBothUsersFromTheTenantsBlock() {
        ClickHouseTenant ch = clickhouse("""
                {"clickhouse": {
                    "database": "acme",
                    "host": "ch-1.example.com",
                    "user": "acme_owner",
                    "password": "owner-secret",
                    "readonly-user": "acme_reader",
                    "readonly-password": "reader-secret"
                }}
                """);

        assertThat(ch.getUsername()).isEqualTo("acme_owner");
        assertThat(ch.getPassword()).isEqualTo("owner-secret");
        assertThat(ch.getReadOnlyUsername()).isEqualTo("acme_reader");
        assertThat(ch.getReadOnlyPassword()).isEqualTo("reader-secret");
        assertThat(ch.hasReadOnlyUser()).isTrue();
    }

    /**
     * A tenant provisioned before the tenant manager created a reader. It has to keep working —
     * the pool falls back to the owner — so the absence must read as "no reader", not as a parse
     * failure or an empty-string user that would then be handed to ClickHouse.
     */
    @Test
    void aBlockWithoutTheReadOnlyKeysHasNoReader() {
        ClickHouseTenant ch = clickhouse("""
                {"clickhouse": {
                    "database": "acme",
                    "host": "ch-1.example.com",
                    "user": "acme_owner",
                    "password": "owner-secret"
                }}
                """);

        assertThat(ch.getUsername()).isEqualTo("acme_owner");
        assertThat(ch.getReadOnlyUsername()).isNull();
        assertThat(ch.hasReadOnlyUser()).isFalse();
    }

    /** A blank user is the same as no user — connecting as "" would just fail later, opaquely. */
    @Test
    void aBlankReadOnlyUserIsNoReader() {
        assertThat(clickhouse("""
                {"clickhouse": {"user": "acme_owner", "readonly-user": "  "}}
                """).hasReadOnlyUser()).isFalse();
    }
}
