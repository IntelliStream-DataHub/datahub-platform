// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.tenant.Neo4jTenant;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantContext;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.*;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class Neo4j implements AutoCloseable {

    private static final int BOLT_PORT = 7687;

    private final TenantConfigService tenantConfigService;

    // One driver (and its connection pool) per distinct Neo4j endpoint+login, keyed by
    // "host|user". Tenants co-located on the same server under the same login share a driver;
    // the per-tenant database is selected at the session level via forDatabase(). Drivers are
    // heavyweight, thread-safe and pooled, so they are built once on first use and reused for
    // the app lifetime rather than created per request.
    private final Map<String, Driver> drivers = new ConcurrentHashMap<>();

    Neo4j(TenantConfigService tenantConfigService) {
        this.tenantConfigService = tenantConfigService;
    }

    @Override
    public void close() {
        drivers.values().forEach(driver -> {
            try {
                driver.close();
            } catch (Exception e) {
                log.warn("Failed to close Neo4j driver: {}", e.getMessage());
            }
        });
        drivers.clear();
    }

    /**
     * Transparently creates a session for the current tenant.
     * @throws RuntimeException if TenantContext is empty.
     */
    public Session getSession() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new RuntimeException("Neo4j Multi-tenancy Error: No Tenant ID found in" +
                    "TenantContext. Ensure the request went through the TenantFilter.");
        }
        return getSession(tenantId);
    }

    public Session getSession(@NotNull String tenantId) {
        Neo4jTenant config = tenantConfigService.getConfig(tenantId).getNeo4jTenant();
        Driver driver = drivers.computeIfAbsent(
                config.getHost() + "|" + config.getUsername(),
                key -> createDriver(config));
        return driver.session(SessionConfig.forDatabase(config.getDatabaseName()));
    }

    private Driver createDriver(Neo4jTenant config) {
        String uri = "bolt://" + config.getHost() + ":" + BOLT_PORT;
        Driver driver = GraphDatabase.driver(
                URI.create(uri),
                AuthTokens.basic(config.getUsername(), config.getPassword()));
        try {
            driver.verifyConnectivity();
        } catch (RuntimeException e) {
            // Don't cache a driver we couldn't reach: close it so its pool doesn't leak and
            // let the exception propagate so the next call retries (computeIfAbsent stores nothing).
            driver.close();
            throw e;
        }
        log.info("Created Neo4j driver for {}", uri);
        return driver;
    }
}
