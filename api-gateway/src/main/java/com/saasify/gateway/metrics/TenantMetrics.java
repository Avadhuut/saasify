package com.saasify.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Custom telemetry metrics registered in Micrometer's MeterRegistry.
 * Tracks total requests and latency metrics dynamically per tenant.
 */
@Component
public class TenantMetrics {

    private final MeterRegistry meterRegistry;

    public TenantMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Increments the 'saasify_gateway_requests_total' counter.
     */
    public void incrementRequestCount(String tenant, String endpoint, String status) {
        Counter.builder("saasify_gateway_requests_total")
                .description("Total requests processed by the API Gateway per tenant")
                .tag("tenant", tenant != null ? tenant : "UNKNOWN")
                .tag("endpoint", endpoint != null ? endpoint : "UNKNOWN")
                .tag("status", status != null ? status : "UNKNOWN")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Records request execution durations in 'saasify_gateway_latency_seconds' summary histogram.
     */
    public void recordLatency(String tenant, String endpoint, double latencySeconds) {
        DistributionSummary.builder("saasify_gateway_latency_seconds")
                .description("API request latency histogram per tenant")
                .tag("tenant", tenant != null ? tenant : "UNKNOWN")
                .tag("endpoint", endpoint != null ? endpoint : "UNKNOWN")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(latencySeconds);
    }
}
