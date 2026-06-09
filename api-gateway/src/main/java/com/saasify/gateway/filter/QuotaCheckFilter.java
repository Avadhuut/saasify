package com.saasify.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.time.LocalDate;

/**
 * Global API Gateway Filter enforcing daily call quotas.
 * Scans Redis daily usage counters and compares against active tenant subscription profiles.
 */
@Component
public class QuotaCheckFilter implements GlobalFilter, Ordered {

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TENANT_HEADER = "X-Tenant-ID";

    private final org.springframework.web.reactive.function.client.WebClient webClient = 
            org.springframework.web.reactive.function.client.WebClient.create("http://localhost:8081");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String tenantId = exchange.getRequest().getHeaders().getFirst(TENANT_HEADER);

        // Allow public/preflight requests to bypass header validations at gateway level
        if (tenantId == null || tenantId.trim().isEmpty()) {
            return chain.filter(exchange);
        }

        String today = LocalDate.now().toString();
        String planCacheKey = "tenant:" + tenantId;
        String usageKey = "usage:" + tenantId + ":" + today;

        return redisTemplate.opsForValue().get(planCacheKey)
                .switchIfEmpty(Mono.defer(() -> {
                    // Cache miss: query tenant-service and cache it in Redis for 5 minutes
                    return webClient.get()
                            .uri("/api/tenants/" + tenantId)
                            .retrieve()
                            .bodyToMono(String.class)
                            .flatMap(tenantJson -> {
                                return redisTemplate.opsForValue().set(planCacheKey, tenantJson, java.time.Duration.ofMinutes(5))
                                        .thenReturn(tenantJson);
                            })
                            .onErrorResume(e -> {
                                System.err.println("Warning: Failed to fetch tenant metadata from tenant-service: " + e.getMessage());
                                return Mono.empty();
                            });
                }))
                .flatMap(tenantJson -> {
                    String status = extractStatus(tenantJson);
                    if ("SUSPENDED".equalsIgnoreCase(status)) {
                        return respondWithSuspended(exchange);
                    }

                    String plan = extractPlan(tenantJson);
                    long limit = getPlanQuotaLimit(plan);

                    if (limit < 0) {
                        return chain.filter(exchange); // Unlimited quota for Enterprise plans
                    }

                    return redisTemplate.opsForValue().get(usageKey)
                            .defaultIfEmpty("0")
                            .flatMap(countStr -> {
                                long currentUsage = Long.parseLong(countStr);
                                if (currentUsage >= limit) {
                                    return respondWithQuotaExceeded(exchange, currentUsage, limit, plan);
                                }
                                return chain.filter(exchange);
                            });
                })
                .switchIfEmpty(chain.filter(exchange)); // Fallback: allow request if cache missing and fetch failed
    }

    private String extractPlan(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.get("plan").asText();
        } catch (Exception e) {
            return "FREE"; // Fallback to FREE plan thresholds on parsing errors
        }
    }

    private String extractStatus(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.has("status") ? node.get("status").asText() : "ACTIVE";
        } catch (Exception e) {
            return "ACTIVE";
        }
    }

    private long getPlanQuotaLimit(String plan) {
        if ("FREE".equalsIgnoreCase(plan)) {
            return 100;
        } else if ("PRO".equalsIgnoreCase(plan)) {
            return 10000;
        }
        return -1; // Unlimited
    }

    private Mono<Void> respondWithSuspended(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.PAYMENT_REQUIRED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = "{\"error\":\"Payment Required\",\"message\":\"Tenant account is suspended. Please resolve billing issues or upgrade plan.\"}";

        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes())));
    }

    private Mono<Void> respondWithQuotaExceeded(ServerWebExchange exchange, long current, long max, String plan) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = String.format(
                "{\"error\":\"Quota Exceeded\",\"message\":\"Daily API call limit reached for plan %s.\",\"currentUsage\":%d,\"maxLimit\":%d,\"planType\":\"%s\",\"upgradeUrl\":\"https://saasify.com/upgrade\"}",
                plan, current, max, plan
        );

        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes())));
    }

    @Override
    public int getOrder() {
        return 1; // Run before routing and rate limit checks
    }
}
