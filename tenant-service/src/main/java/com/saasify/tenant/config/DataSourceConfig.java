package com.saasify.tenant.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration class that sets up Spring Data JPA and wires in our MultiTenantRoutingDataSource.
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "com.saasify.tenant.repository",
        entityManagerFactoryRef = "entityManagerFactory",
        transactionManagerRef = "transactionManager"
)
public class DataSourceConfig {

    /**
     * Exposes spring.datasource configuration properties mapping.
     */
    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * Initializes the master connection pool pointing to saasify_master.
     */
    @Bean
    @Primary
    public DataSource masterDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    /**
     * Configures the MultiTenantRoutingDataSource instance wrapping the master pool as default fallback.
     */
    @Bean
    public MultiTenantRoutingDataSource routingDataSource(DataSource masterDataSource) {
        MultiTenantRoutingDataSource routingDataSource = new MultiTenantRoutingDataSource(masterDataSource);
        // Pre-register master target datasource
        routingDataSource.addTenantDataSource("master", masterDataSource);
        routingDataSource.setDefaultTargetDataSource(masterDataSource);
        routingDataSource.afterPropertiesSet();
        return routingDataSource;
    }

    /**
     * Configures the JPA LocalContainerEntityManagerFactoryBean targetting the routing datasource.
     */
    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(MultiTenantRoutingDataSource routingDataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(routingDataSource);
        em.setPackagesToScan("com.saasify.tenant.model");

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        em.setJpaVendorAdapter(vendorAdapter);

        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.hbm2ddl.auto", "none"); // Schema migrations are handled strictly via Flyway
        properties.put("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
        properties.put("hibernate.show_sql", "true");
        properties.put("hibernate.format_sql", "true");

        em.setJpaPropertyMap(properties);
        return em;
    }

    /**
     * Configures the PlatformTransactionManager to handle transactions across JPA operations.
     */
    @Bean
    public PlatformTransactionManager transactionManager(LocalContainerEntityManagerFactoryBean entityManagerFactory) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(entityManagerFactory.getObject());
        return transactionManager;
    }
}
