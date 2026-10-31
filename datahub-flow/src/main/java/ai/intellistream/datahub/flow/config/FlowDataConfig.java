// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.config;

import ai.intellistream.datahub.tenant.StatelessRoutingDataSource;
import ai.intellistream.datahub.tenant.TenantConfigService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * The tenant's own Postgres, chosen per connection from {@code TenantContext}, as in every other
 * service. Spring Boot puts a {@code JdbcTemplate} and a {@code DataSourceTransactionManager} on top
 * of it; there is no JPA here.
 *
 * <p>No pool: pgbouncer in transaction mode pools in front of Postgres. So whatever needs a lock to
 * hold — a {@code SELECT … FOR UPDATE SKIP LOCKED} and the updates after it — must run in one
 * transaction, which keeps one backend for its duration, and nothing may rely on session state.
 */
@Configuration
public class FlowDataConfig {

    @Bean
    DataSource dataSource(TenantConfigService configService) {
        return new StatelessRoutingDataSource(configService);
    }
}
