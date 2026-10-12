// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.tenant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a tenant's database has the {@code flow} schema yet.
 *
 * <p>The schema is created by the platform's per-tenant Flyway migrations, which datahub-api runs
 * and datahub-cleanup retries. This service only looks: running migrations as well would make it a
 * third migrator of every tenant's database. So until the api has migrated a tenant, the flow
 * service refuses its requests with a 503 rather than failing on a missing table.
 *
 * <p>A tenant is checked with one statement until the schema is there, then remembered for the life
 * of the JVM: a schema is never dropped, so the answer cannot go back to no.
 */
@Slf4j
@Component
public class FlowSchemaCheck {

    private final JdbcTemplate jdbc;
    private final Set<String> provisioned = ConcurrentHashMap.newKeySet();

    public FlowSchemaCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Whether the current tenant ({@code TenantContext}) has the {@code flow} schema. A database that
     * cannot be reached counts as not provisioned: the caller gets a 503 and retries.
     */
    public boolean isProvisioned(String tenantId) {
        if (provisioned.contains(tenantId)) {
            return true;
        }
        try {
            Boolean present = jdbc.queryForObject(
                    "SELECT to_regclass('flow.flow') IS NOT NULL", Boolean.class);
            if (Boolean.TRUE.equals(present)) {
                provisioned.add(tenantId);
                return true;
            }
        } catch (DataAccessException e) {
            log.warn("Could not check the flow schema of tenant {}: {}", tenantId, e.getMessage());
        }
        return false;
    }
}
