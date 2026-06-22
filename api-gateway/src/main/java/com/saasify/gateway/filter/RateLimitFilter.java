package com.saasify.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Edge API Gateway Filter enforcing per-minute rate limits.
 * Implements a distributed rate-limiting window mapping to 'ratelimit:{tenantId}:{windowStart}'.
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    @Autowired
    private ReactiveRedisOperations<String, String> redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final DateTimeFormatter MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String tenantId = exchange.getRequest().getHeaders().getFirst(TENANT_HEADER);

        // Allow preflight/public requests without headers to pass gateway check
        if (tenantId == null || tenantId.trim().isEmpty()) {
            return chain.filter(exchange);
        }

        String windowStart = LocalDateTime.now().format(MINUTE_FORMATTER);
        String rateLimitKey = "ratelimit:" + tenantId + ":" + windowStart;
        String planCacheKey = "tenant:" + tenantId;

        return redisTemplate.opsForValue().get(planCacheKey)
                .defaultIfEmpty("{\"plan\":\"FREE\"}") // Fallback to FREE plan limits
                .flatMap(tenantJson -> {
                    String plan = extractPlan(tenantJson);
                    long maxLimit = getMinuteLimit(plan);

                    return redisTemplate.opsForValue().increment(rateLimitKey)
                            .flatMap(count -> {
                                if (count == 1) {
                                    // Set sliding 60-second window expire duration for the newly created bucket
                                    return redisTemplate.expire(rateLimitKey, Duration.ofSeconds(60))
                                            .then(evaluateLimit(exchange, chain, count, maxLimit, plan));
                                }
                                return evaluateLimit(exchange, chain, count, maxLimit, plan);
                            });
                });
    }

    private Mono<Void> evaluateLimit(ServerWebExchange exchange, GatewayFilterChain chain, long current, long max, String plan) {
        if (current > max) {
            return respondWithRateLimitExceeded(exchange, plan, max);
        }
        return chain.filter(exchange);
    }

    private String extractPlan(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.get("plan").asText();
        } catch (Exception e) {
            return "FREE";
        }
    }

    private long getMinuteLimit(String plan) {
        if ("FREE".equalsIgnoreCase(plan)) {
            return 10;
        } else if ("PRO".equalsIgnoreCase(plan)) {
            return 100;
        } else if ("ENTERPRISE".equalsIgnoreCase(plan)) {
            return 1000;
        }
        return 10; // Default fallback to FREE limit
    }

    private Mono<Void> respondWithRateLimitExceeded(ServerWebExchange exchange, String plan, long maxLimit) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        int secondsRemaining = 60 - java.time.LocalTime.now().getSecond();
        response.getHeaders().add("Retry-After", String.valueOf(secondsRemaining));

        String body = String.format(
                "{\"error\":\"Rate Limit Exceeded\",\"message\":\"Request limit exceeded for plan %s. Max allowed is %d requests per minute.\",\"limitPerMinute\":%d}",
                plan, maxLimit, maxLimit
        );

        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes())));
    }

    @Override
    public int getOrder() {
        return 2; // Executed after QuotaCheckFilter (daily quota evaluations)
    }
}
