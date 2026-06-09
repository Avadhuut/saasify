package com.saasify.tenant.service;

import com.saasify.tenant.config.MultiTenantRoutingDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Service responsible for provisioning isolated databases (schemas) for newly registered tenants
 * and running Flyway migrations on those schemas programmatically.
 */
@Service
public class SchemaProvisioningService {

    @Autowired
    private DataSource masterDataSource;

    @Autowired
    private MultiTenantRoutingDataSource routingDataSource;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    /**
     * Creates a new database schema and runs Flyway migrations using the template blueprint.
     *
     * @param subdomain the tenant subdomain which determines the schema name
     */
    public void provisionSchema(String subdomain) {
        String schemaName = "tenant_" + subdomain;

        // 1. Execute native SQL to create the isolated schema
        try (Connection connection = masterDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS " + schemaName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to programmatically create database schema: " + schemaName, e);
        }

        // 2. Programmatically execute Flyway migrations on the new schema using the template script
        String tenantDbUrl = resolveTenantUrl(schemaName);
        
        Flyway flyway = Flyway.configure()
                .dataSource(tenantDbUrl, dbUsername, dbPassword)
                .locations("classpath:db/template")
                .schemas(schemaName)
                .load();

        flyway.migrate();

        // 3. Create connection pool and register with Routing DataSource so it's live instantly
        DataSource tenantDataSource = createTenantDataSource(tenantDbUrl, subdomain);
        routingDataSource.addTenantDataSource(subdomain, tenantDataSource);
    }

    /**
     * Resolves the target database URL by replacing 'saasify_master' with the tenant schema name.
     */
    private String resolveTenantUrl(String schemaName) {
        String masterPlaceholder = "saasify_master";
        if (!dbUrl.contains(masterPlaceholder)) {
            throw new IllegalStateException("Database URL does not contain '" + masterPlaceholder + "' placeholder.");
        }
        int index = dbUrl.indexOf(masterPlaceholder);
        String prefix = dbUrl.substring(0, index);
        String suffix = dbUrl.substring(index + masterPlaceholder.length());
        return prefix + schemaName + suffix;
    }

    /**
     * Configures a connection pool (HikariDataSource) for the tenant schema.
     */
    private DataSource createTenantDataSource(String tenantDbUrl, String subdomain) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(tenantDbUrl);
        ds.setUsername(dbUsername);
        ds.setPassword(dbPassword);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setPoolName("HikariPool-tenant_" + subdomain);
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        ds.setIdleTimeout(30000);
        return ds;
    }
}
