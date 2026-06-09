package com.saasify.auth.config;

import org.springframework.beans.factory.annotation.Value;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration class that maps Spring Data JPA to use the MultiTenantRoutingDataSource in auth-service,
 * and exposes utility methods to dynamically initialize tenant connection pools from the master metadata.
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "com.saasify.auth.repository",
        entityManagerFactoryRef = "entityManagerFactory",
        transactionManagerRef = "transactionManager"
)
public class MultiTenantDataSourceConfig {

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource masterDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    public MultiTenantRoutingDataSource routingDataSource(DataSource masterDataSource) {
        MultiTenantRoutingDataSource routingDataSource = new MultiTenantRoutingDataSource(masterDataSource);
        routingDataSource.addTenantDataSource("master", masterDataSource);
        routingDataSource.setDefaultTargetDataSource(masterDataSource);
        routingDataSource.afterPropertiesSet();
        return routingDataSource;
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(MultiTenantRoutingDataSource routingDataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(routingDataSource);
        em.setPackagesToScan("com.saasify.auth.entity");

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        em.setJpaVendorAdapter(vendorAdapter);

        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.hbm2ddl.auto", "none");
        properties.put("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
        properties.put("hibernate.show_sql", "true");
        properties.put("hibernate.format_sql", "true");

        em.setJpaPropertyMap(properties);
        return em;
    }

    @Bean
    public PlatformTransactionManager transactionManager(LocalContainerEntityManagerFactoryBean entityManagerFactory) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(entityManagerFactory.getObject());
        return transactionManager;
    }

    /**
     * Resolves the schema name for the given tenant ID and registers a new Hikari connection pool dynamically.
     * Synchronized to prevent duplicate pool initializations from concurrent requests.
     */
    public synchronized void registerTenantDataSource(String tenantId, MultiTenantRoutingDataSource routingDataSource, DataSource masterDataSource) {
        if (routingDataSource.isTenantRegistered(tenantId)) {
            return;
        }

        String schemaName = null;
        try (Connection connection = masterDataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT schema_name FROM tenants WHERE subdomain = ? AND status = 'ACTIVE'")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    schemaName = rs.getString("schema_name");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error looking up tenant schema metadata for: " + tenantId, e);
        }

        if (schemaName == null) {
            throw new IllegalArgumentException("Tenant '" + tenantId + "' is not registered or is inactive on the platform.");
        }

        // Dynamically build Hikari connection pool targetting the tenant schema
        String masterPlaceholder = "saasify_master";
        if (!dbUrl.contains(masterPlaceholder)) {
            throw new IllegalStateException("Database URL configuration does not contain '" + masterPlaceholder + "' placeholder.");
        }
        int index = dbUrl.indexOf(masterPlaceholder);
        String prefix = dbUrl.substring(0, index);
        String suffix = dbUrl.substring(index + masterPlaceholder.length());
        String tenantDbUrl = prefix + schemaName + suffix;

        com.zaxxer.hikari.HikariDataSource ds = new com.zaxxer.hikari.HikariDataSource();
        ds.setJdbcUrl(tenantDbUrl);
        ds.setUsername(dbUsername);
        ds.setPassword(dbPassword);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setPoolName("HikariPool-auth-tenant_" + tenantId);
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        ds.setIdleTimeout(30000);

        routingDataSource.addTenantDataSource(tenantId, ds);
    }
}
