package com.saasify.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TenantMetricsImplTest {

    private MeterRegistry meterRegistry;
    private TenantMetricsImpl tenantMetrics;

    @BeforeEach
    public void setUp() {
        // Use SimpleMeterRegistry (a standard in-memory registry) to avoid mocking MeterRegistry
        meterRegistry = new SimpleMeterRegistry();
        tenantMetrics = new TenantMetricsImpl(meterRegistry);
    }

    @Test
    public void testIncrementRequestCount() {
        tenantMetrics.incrementRequestCount("acme", "/users", "200");

        Counter counter = meterRegistry.find("saasify_gateway_requests_total")
                .tag("tenant", "acme")
                .tag("endpoint", "/users")
                .tag("status", "200")
                .counter();

        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    public void testRecordLatency() {
        tenantMetrics.recordLatency("acme", "/users", 0.150);

        DistributionSummary summary = meterRegistry.find("saasify_gateway_latency_seconds")
                .tag("tenant", "acme")
                .tag("endpoint", "/users")
                .summary();

        assertNotNull(summary);
        assertEquals(1.0, summary.count());
        assertEquals(0.150, summary.totalAmount(), 0.001);
    }
}
