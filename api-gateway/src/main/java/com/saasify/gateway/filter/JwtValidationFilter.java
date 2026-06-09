package com.saasify.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.security.Key;

/**
 * Global API Gateway Filter validating JWT signatures and ensuring tenant scope integrity.
 * Prevents cross-tenant token hijacking by comparing token claims with X-Tenant-ID header.
 */
@Component
public class JwtValidationFilter implements GlobalFilter, Ordered {

    @Value("${jwt.secret:9a72632b512c3f84812a67e5a6c12b7a9f826312a3d76e82faef0129a834fb1c}")
    private String secret;

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String USER_HEADER = "X-User-ID";

    private Key getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Bypass security checks for public auth endpoints and tenant onboarding endpoints
        if (path.startsWith("/api/auth/login") || 
            path.startsWith("/api/auth/register") || 
            path.startsWith("/api/auth/refresh") ||
            path.startsWith("/api/tenants")) {
            return chain.filter(exchange);
        }

        // Only enforce token validation on protected endpoints
        if (!path.startsWith("/api/users") && 
            !path.startsWith("/api/billing") && 
            !path.startsWith("/api/auth/logout")) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(AUTHORIZATION_HEADER);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return respondWithError(exchange, HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String tokenTenantId = claims.get("tenantId", String.class);
            String tokenUserId = claims.get("userId", String.class);
            String requestTenantId = exchange.getRequest().getHeaders().getFirst(TENANT_HEADER);

            // Cross-Tenant Token Rejection (Goal 4.2)
            if (requestTenantId != null && !requestTenantId.equalsIgnoreCase(tokenTenantId)) {
                return respondWithError(exchange, HttpStatus.FORBIDDEN, "Access Denied: Token scope does not match X-Tenant-ID header");
            }

            // Propagate the authenticated User ID down to the microservices
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header(USER_HEADER, tokenUserId != null ? tokenUserId : "ANONYMOUS")
                    .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());

        } catch (Exception e) {
            return respondWithError(exchange, HttpStatus.UNAUTHORIZED, "Invalid, expired, or tampered access token");
        }
    }

    private Mono<Void> respondWithError(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"error\":\"%s\",\"message\":\"%s\"}", status.getReasonPhrase(), message);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes())));
    }

    @Override
    public int getOrder() {
        return -5; // Execute early, before QuotaCheckFilter (Order 1) and RateLimitFilter (Order 2)
    }
}
