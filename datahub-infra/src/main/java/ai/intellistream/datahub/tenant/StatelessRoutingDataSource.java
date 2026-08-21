// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.tenant;

import org.jspecify.annotations.NonNull;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.Properties;

public class StatelessRoutingDataSource extends AbstractRoutingDataSource {

    private final TenantConfigService configService;

    public StatelessRoutingDataSource(TenantConfigService configService) {
        this.configService = configService;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return TenantContext.getTenantId();
    }

    @SuppressWarnings("NullableProblems")
    @Override
    protected DataSource determineTargetDataSource() {
        String tenantId = (String) determineCurrentLookupKey();

        // Validation
        if (tenantId == null) throw new IllegalStateException("No Tenant ID in context");
        Tenant config = configService.getConfig(tenantId);
        if (config == null) throw new UnknownTenantException(tenantId);

        return getSimpleDriverDataSource(config);
    }

    // No app-side connection pool: a pgbouncer on localhost fronts Postgres and handles pooling,
    // so each getConnection() is a cheap localhost handshake that pgbouncer multiplexes onto a
    // bounded backend pool.
    // SimpleDriverDataSource vs. SingleConnectionDataSource
    // They have the same behavior for Transactional, but SimpleDriverDataSource
    // is safer when you forget to use Transactional
    private static @NonNull SimpleDriverDataSource getSimpleDriverDataSource(Tenant config) {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(config.getPostgresTenant().getJdbcUrl());
        ds.setUsername(config.getPostgresTenant().getUsername());
        String password = config.getPostgresTenant().getPassword();
        ds.setPassword(password != null ? password : "");
        Properties props = new Properties();
        props.setProperty("connectTimeout", "10");
        props.setProperty("socketTimeout", "30");
        ds.setConnectionProperties(props);
        return ds;
    }

    @Override
    public void afterPropertiesSet() {
        // No default datasource needed
    }
}
