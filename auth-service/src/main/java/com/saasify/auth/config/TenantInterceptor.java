package com.saasify.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.sql.DataSource;

/**
 * Interceptor that intercepts HTTP requests, extracts the 'X-Tenant-ID' header,
 * programmatically registers the tenant database connection if not already cached,
 * binds it to TenantContext, and clears it on completion.
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    private static final String TENANT_HEADER = "X-Tenant-ID";

    @Autowired
    private MultiTenantRoutingDataSource routingDataSource;

    @Autowired
    private MultiTenantDataSourceConfig dataSourceConfig;

    @Autowired
    private DataSource masterDataSource;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Option to bypass interceptor check for options preflight requests
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String tenantId = request.getHeader(TENANT_HEADER);

        if (tenantId == null || tenantId.trim().isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Missing required HTTP header: " + TENANT_HEADER + "\"}");
            return false;
        }

        try {
            // Verify tenant is registered in saasify_master and initialize its datasource pool
            dataSourceConfig.registerTenantDataSource(tenantId, routingDataSource, masterDataSource);
            
            // Set context for Hibernate lookup
            TenantContext.setCurrentTenant(tenantId);
            return true;
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"" + e.getMessage() + "\"}");
            return false;
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"Failed to resolve tenant database pool: " + e.getMessage() + "\"}");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // MANDATORY: Clear ThreadLocal reference to prevent memory leaks and cross-tenant leak vulnerabilities
        TenantContext.clear();
    }
}
