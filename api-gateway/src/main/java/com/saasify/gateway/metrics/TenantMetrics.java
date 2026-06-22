package com.saasify.gateway.metrics;

/**
 * Interface defining Gateway per-tenant metrics operations for distributed tracing.
 * Utilizing an interface allows mock frameworks to easily resolve bytecode on modern JDK versions.
 */
public interface TenantMetrics {
    void incrementRequestCount(String tenant, String endpoint, String status);
    void recordLatency(String tenant, String endpoint, double latencySeconds);
}
