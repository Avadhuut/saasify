package com.saasify.auth.config;

/**
 * Utility class wrapping a ThreadLocal variable to hold and manage the active request's tenantId (subdomain) inside auth-service.
 */
public final class TenantContext {

    private TenantContext() {
        // Prevent instantiation of utility class
    }

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    public static void setCurrentTenant(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getCurrentTenant() {
        return CURRENT_TENANT.get();
    }

    /**
     * Clear the current tenant identifier.
     * 
     * MANDATORY SAFETY NOTICE:
     * Must be called in a 'finally' block when serving web requests to prevent context leakage
     * between different client threads in Tomcat thread pools.
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
