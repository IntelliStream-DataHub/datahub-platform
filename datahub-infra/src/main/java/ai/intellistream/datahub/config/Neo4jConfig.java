// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.tenant.TenantConfigService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class Neo4jConfig {

    @Primary
    @Bean
    public Neo4j neo4j(TenantConfigService tenantConfigService) {
        return new Neo4j(tenantConfigService);
    }
}
