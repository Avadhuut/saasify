package com.saasify.gateway.filter;

import com.saasify.gateway.metrics.TenantMetrics;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import io.opentelemetry.api.trace.Span;

import java.util.UUID;

@Component
public class ObservabilityFilter implements GlobalFilter, Ordered {

    @Autowired
    private TenantMetrics tenantMetrics;

    @Autowired(required = false)
    private org.springframework.kafka.core.KafkaTemplate<String, String> kafkaTemplate;

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String USER_HEADER = "X-User-ID";


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startTime = System.nanoTime();

        // 1. Resolve tenantId
        String tenantId = exchange.getRequest().getHeaders().getFirst(TENANT_HEADER);
        if (tenantId == null || tenantId.trim().isEmpty()) {
            // Fallback: extract subdomain from Host header
            String host = exchange.getRequest().getHeaders().getFirst("Host");
            if (host != null && host.contains(".")) {
                String[] parts = host.split("\\.");
                if (parts.length > 1) {
                    tenantId = parts[0];
                }
            }
        }
        if (tenantId == null) {
            tenantId = "SYSTEM";
        }

        // 2. Resolve correlationId
        String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_HEADER);
        if (correlationId == null || correlationId.trim().isEmpty()) {
            correlationId = UUID.randomUUID().toString();
        }

        // 3. Resolve userId
        String userId = exchange.getRequest().getHeaders().getFirst(USER_HEADER);
        if (userId == null) {
            userId = "ANONYMOUS";
        }

        // 4. Populate current thread SLF4J MDC
        MDC.put("tenantId", tenantId);
        MDC.put("correlationId", correlationId);
        MDC.put("userId", userId);

        // 5. Enrich current OpenTelemetry tracing span
        Span.current().setAttribute("tenant.id", tenantId);
        Span.current().setAttribute("correlation.id", correlationId);
        Span.current().setAttribute("user.id", userId);

        // 6. Mutate downstream request headers
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(TENANT_HEADER, tenantId)
                .header(CORRELATION_HEADER, correlationId)
                .header(USER_HEADER, userId)
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        final String finalTenantId = tenantId;
        final String path = exchange.getRequest().getPath().value();

        // 7. Execute chain and record metrics
        return chain.filter(mutatedExchange)
                .doOnSuccess(aVoid -> {
                    long durationNs = System.nanoTime() - startTime;
                    double durationSeconds = durationNs / 1_000_000_000.0;
                    String status = "200";
                    if (mutatedExchange.getResponse().getStatusCode() != null) {
                        status = String.valueOf(mutatedExchange.getResponse().getStatusCode().value());
                    }
                    tenantMetrics.incrementRequestCount(finalTenantId, path, status);
                    tenantMetrics.recordLatency(finalTenantId, path, durationSeconds);
                    
                    // Asynchronously publish usage record to Kafka topic for billing-service to consume
                    if (kafkaTemplate != null && !"SYSTEM".equalsIgnoreCase(finalTenantId)) {
                        try {
                            kafkaTemplate.send("usage.recorded", finalTenantId);
                        } catch (Exception e) {
                            System.err.println("Warning: ObservabilityFilter failed to publish usage to Kafka: " + e.getMessage());
                        }
                    }
                })

                .doOnError(throwable -> {
                    long durationNs = System.nanoTime() - startTime;
                    double durationSeconds = durationNs / 1_000_000_000.0;
                    tenantMetrics.incrementRequestCount(finalTenantId, path, "500");
                    tenantMetrics.recordLatency(finalTenantId, path, durationSeconds);
                })
                .doFinally(signalType -> {
                    MDC.clear();
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE; // Execute first to set up tracing & log contexts
    }
}
