package com.saasify.gateway.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class JwtValidationFilterTest {

    @InjectMocks
    private JwtValidationFilter jwtValidationFilter;

    @Mock
    private ServerWebExchange exchange;

    @Mock
    private GatewayFilterChain chain;

    @Mock
    private ServerHttpRequest request;

    @Mock
    private ServerHttpRequest.Builder requestBuilder;

    @Mock
    private ServerHttpResponse response;

    @Mock
    private ServerWebExchange.Builder exchangeBuilder;

    private final HttpHeaders httpHeaders = new HttpHeaders();

    private final String secret = "9a72632b512c3f84812a67e5a6c12b7a9f826312a3d76e82faef0129a834fb1c";

    @BeforeEach
    public void setUp() {
        lenient().when(exchange.getRequest()).thenReturn(request);
        lenient().when(request.getHeaders()).thenReturn(httpHeaders);
        lenient().when(exchange.getResponse()).thenReturn(response);
        lenient().when(response.getHeaders()).thenReturn(new HttpHeaders());
        
        // Setup reflection for secret since @Value is not resolved in unit test
        org.springframework.test.util.ReflectionTestUtils.setField(jwtValidationFilter, "secret", secret);
    }

    private String createToken(String tenantId, String userId, long ttlMs) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        Key key = Keys.hmacShaKeyFor(keyBytes);
        return Jwts.builder()
                .claim("tenantId", tenantId)
                .claim("userId", userId)
                .setExpiration(new Date(System.currentTimeMillis() + ttlMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    public void testFilter_BypassesPublicEndpoints() {
        lenient().when(request.getPath()).thenReturn(org.springframework.http.server.RequestPath.parse("/api/auth/login", null));
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        Mono<Void> result = jwtValidationFilter.filter(exchange, chain);

        StepVerifier.create(result).verifyComplete();
        verify(chain, times(1)).filter(exchange);
    }

    @Test
    public void testFilter_MissingAuthorizationHeader_ReturnsUnauthorized() {
        lenient().when(request.getPath()).thenReturn(org.springframework.http.server.RequestPath.parse("/api/users", null));
        httpHeaders.clear();
        when(response.bufferFactory()).thenReturn(org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance);
        when(response.writeWith(any())).thenReturn(Mono.empty());

        Mono<Void> result = jwtValidationFilter.filter(exchange, chain);

        StepVerifier.create(result).verifyComplete();
        verify(response, times(1)).setStatusCode(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(exchange);
    }

    @Test
    public void testFilter_MismatchingTenant_ReturnsForbidden() {
        lenient().when(request.getPath()).thenReturn(org.springframework.http.server.RequestPath.parse("/api/users", null));
        String token = createToken("acme", "user-1", 10000);
        httpHeaders.set("Authorization", "Bearer " + token);
        httpHeaders.set("X-Tenant-ID", "globex"); // Mismatch
        when(response.bufferFactory()).thenReturn(org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance);
        when(response.writeWith(any())).thenReturn(Mono.empty());

        Mono<Void> result = jwtValidationFilter.filter(exchange, chain);

        StepVerifier.create(result).verifyComplete();
        verify(response, times(1)).setStatusCode(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(exchange);
    }

    @Test
    public void testFilter_MatchingTenant_PropagatesUserHeader() {
        lenient().when(request.getPath()).thenReturn(org.springframework.http.server.RequestPath.parse("/api/users", null));
        String token = createToken("acme", "user-1", 10000);
        httpHeaders.set("Authorization", "Bearer " + token);
        httpHeaders.set("X-Tenant-ID", "acme"); // Match

        lenient().when(request.mutate()).thenReturn(requestBuilder);
        lenient().when(requestBuilder.header(anyString(), any())).thenReturn(requestBuilder);
        lenient().when(requestBuilder.build()).thenReturn(request);

        lenient().when(exchange.mutate()).thenReturn(exchangeBuilder);
        lenient().when(exchangeBuilder.request(any(ServerHttpRequest.class))).thenReturn(exchangeBuilder);
        lenient().when(exchangeBuilder.build()).thenReturn(exchange);

        when(chain.filter(exchange)).thenReturn(Mono.empty());

        Mono<Void> result = jwtValidationFilter.filter(exchange, chain);

        StepVerifier.create(result).verifyComplete();
        verify(requestBuilder).header("X-User-ID", "user-1");
        verify(chain, times(1)).filter(exchange);
    }
}
