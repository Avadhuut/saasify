package com.saasify.user.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic routing DataSource extending Spring's AbstractRoutingDataSource for user-service.
 */
public class MultiTenantRoutingDataSource extends AbstractRoutingDataSource {

    private final Map<Object, Object> targetDataSources = new ConcurrentHashMap<>();

    public MultiTenantRoutingDataSource(DataSource defaultTargetDataSource) {
        setDefaultTargetDataSource(defaultTargetDataSource);
        setTargetDataSources(targetDataSources);
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return TenantContext.getCurrentTenant();
    }

    public void addTenantDataSource(String tenantId, DataSource dataSource) {
        targetDataSources.put(tenantId, dataSource);
        setTargetDataSources(targetDataSources);
        afterPropertiesSet();
    }

    public boolean isTenantRegistered(String tenantId) {
        return targetDataSources.containsKey(tenantId);
    }
}
