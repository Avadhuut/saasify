package com.saasify.tenant.config;

/**
 * Utility class wrapping a ThreadLocal variable to hold and manage the active request's tenantId (subdomain).
 */
public final class TenantContext {

    private TenantContext() {
        // Prevent instantiation of utility class
    }

    /**
     * ThreadLocal holding the current tenant identifier.
     * InheritableThreadLocal is avoided here to prevent leakage into child threads unless explicitly required.
     */
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    /**
     * Set the current tenant identifier.
     *
     * @param tenantId the tenant subdomain or identifier
     */
    public static void setCurrentTenant(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    /**
     * Retrieve the current tenant identifier.
     *
     * @return the tenant subdomain or identifier, or null if not set
     */
    public static String getCurrentTenant() {
        return CURRENT_TENANT.get();
    }

    /**
     * Clear the current tenant identifier.
     * 
     * IMPORTANT SAFETY WARNING:
     * In modern web servers (e.g., Tomcat, Undertow), threads are drawn from a reusable thread pool
     * to process incoming HTTP requests. If we do not clean up the ThreadLocal state when a request finishes,
     * the tenant context will remain bound to that thread. 
     * 
     * When the same thread is subsequently reused to serve a completely different user request, that request
     * would default to using the previous request's tenant context. This leads to a critical security
     * vulnerability—cross-tenant data leakage where one customer's request accesses another customer's database.
     * 
     * Therefore, calling clear() inside a 'finally' block of a filter, interceptor, or request handler
     * is absolutely mandatory to prevent memory leaks and protect tenant isolation boundaries.
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
