package com.saasify.user.config;

/**
 * Thread-safe utility to manage active tenant subdomains inside user-service.
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
     * Must be called in a 'finally' block when serving web requests to prevent context leaks
     * in thread pools.
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
