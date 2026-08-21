// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.tenant.StatelessRoutingDataSource;
import ai.intellistream.datahub.tenant.TenantConfigService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.hibernate.SpringImplicitNamingStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.Database;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.HashMap;

@Configuration
@EnableTransactionManagement
// Add this annotation to tell Spring where to find your repositories
@EnableJpaRepositories(
        basePackages = "ai.intellistream.datahub",
        entityManagerFactoryRef = "entityManagerFactory",
        transactionManagerRef = "transactionManager"
)
public class AppConfig {

    private final Environment env;
    private final boolean showSql;
    private final boolean formatSql;

    AppConfig(Environment environment,
                     @Value("${spring.jpa.show-sql:true}")
                     boolean showSql,
                     @Value("${spring.jpa.properties.hibernate.format_sql:true}")
                     boolean formatSql
    ) {
        this.env = environment;
        this.showSql = showSql;
        this.formatSql = formatSql;
    }

    @Bean
    public DataSource dataSource(TenantConfigService configService) {
        return new StatelessRoutingDataSource(configService);
    }

    @Bean
    public JpaVendorAdapter jpaVendorAdapter() {
        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setDatabase(Database.POSTGRESQL);
        adapter.setDatabasePlatform("org.hibernate.dialect.PostgreSQLDialect");
        adapter.setGenerateDdl(false);
        adapter.setShowSql(true);
        return adapter;
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan(
                "ai.intellistream.datahub.jpa.domains",
                "ai.intellistream.datahub.jpa.repositories"
        );

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setDatabasePlatform("org.hibernate.dialect.PostgreSQLDialect");
        em.setJpaVendorAdapter(vendorAdapter);

        HashMap<String, Object> props = new HashMap<>();
        props.put(
                "hibernate.hbm2ddl.auto",
                env.getProperty("spring.jpa.hibernate.ddl-auto", "none")
        );

        // Set dialect
        String dialect = env.getProperty("spring.jpa.hibernate.dialect");
        if (dialect == null) {
            dialect = "org.hibernate.dialect.PostgreSQLDialect";
        }
        props.put("hibernate.dialect", dialect);

        props.put(
                "hibernate.physical_naming_strategy",
                PhysicalNamingStrategySnakeCaseImpl.class.getName()
        );
        props.put(
                "hibernate.implicit_naming_strategy",
                SpringImplicitNamingStrategy.class.getName()
        );
        props.put(
                "hibernate.show_sql",
                showSql
        );
        props.put(
                "hibernate.format_sql",
                formatSql
        );
        props.put("hibernate.jdbc.batch_size", env.getProperty("spring.jpa.properties.hibernate.jdbc.batch_size"));
        props.put("hibernate.order_inserts", env.getProperty("spring.jpa.properties.hibernate.order_inserts"));
        props.put("hibernate.order_updates", env.getProperty("spring.jpa.properties.hibernate.order_updates"));

        props.put("hibernate.generate_statistics", env.getProperty("spring.jpa.properties.hibernate.generate_statistics"));

        // CRITICAL FIX: Disable Hibernate's startup connection check
        // This prevents the "No Tenant ID" error without needing a default datasource
        props.put("hibernate.temp.use_jdbc_metadata_defaults", "false");
        props.put("hibernate.boot.allow_jdbc_metadata_access", "false");

        em.setJpaPropertyMap(props);
        return em;
    }

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(entityManagerFactory);
        return transactionManager;
    }

}
