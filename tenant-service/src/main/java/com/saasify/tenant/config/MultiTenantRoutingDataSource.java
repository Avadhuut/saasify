package com.saasify.tenant.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic routing DataSource class extending Spring's AbstractRoutingDataSource.
 * It resolves the target database dynamically based on the current context lookup key.
 */
public class MultiTenantRoutingDataSource extends AbstractRoutingDataSource {

    // Thread-safe map to store registered target datasources for each tenant
    private final Map<Object, Object> targetDataSources = new ConcurrentHashMap<>();

    /**
     * Constructor initializing the routing datasource with a default datasource.
     *
     * @param defaultTargetDataSource the master connection pool datasource
     */
    public MultiTenantRoutingDataSource(DataSource defaultTargetDataSource) {
        setDefaultTargetDataSource(defaultTargetDataSource);
        setTargetDataSources(targetDataSources);
    }

    /**
     * Resolves the current tenant identifier from the ThreadLocal TenantContext.
     *
     * @return the tenant id lookup key
     */
    @Override
    protected Object determineCurrentLookupKey() {
        return TenantContext.getCurrentTenant();
    }

    /**
     * Programmatically registers a new tenant datasource connection pool at runtime.
     * This allows adding new tenants dynamically without restarting the application.
     *
     * @param tenantId   the tenant subdomain/id key
     * @param dataSource the dynamic datasource connection pool
     */
    public void addTenantDataSource(String tenantId, DataSource dataSource) {
        targetDataSources.put(tenantId, dataSource);
        // Inform the parent class about the updated target datasources map
        setTargetDataSources(targetDataSources);
        // Refresh the parent routing datasource internal lookup mapping
        afterPropertiesSet();
    }

    /**
     * Checks if a tenant datasource is already registered.
     *
     * @param tenantId the tenant subdomain/id key
     * @return true if the datasource is present
     */
    public boolean isTenantRegistered(String tenantId) {
        return targetDataSources.containsKey(tenantId);
    }
}
