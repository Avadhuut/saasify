package com.saasify.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Concrete implementation of TenantMetrics interface recording Gateway metrics inside Micrometer registry.
 */
@Component
public class TenantMetricsImpl implements TenantMetrics {

    private final MeterRegistry meterRegistry;

    public TenantMetricsImpl(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void incrementRequestCount(String tenant, String endpoint, String status) {
        Counter.builder("saasify_gateway_requests_total")
                .description("Total requests processed by the API Gateway per tenant")
                .tag("tenant", tenant != null ? tenant : "UNKNOWN")
                .tag("endpoint", endpoint != null ? endpoint : "UNKNOWN")
                .tag("status", status != null ? status : "UNKNOWN")
                .register(meterRegistry)
                .increment();
    }

    @Override
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
