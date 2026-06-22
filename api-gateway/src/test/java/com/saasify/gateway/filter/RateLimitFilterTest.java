package com.saasify.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RateLimitFilterTest {

    @InjectMocks
    private RateLimitFilter rateLimitFilter;

    @Mock
    private ReactiveRedisOperations<String, String> redisTemplate;

    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    @Mock
    private ServerWebExchange exchange;

    @Mock
    private GatewayFilterChain chain;

    @Mock
    private ServerHttpRequest request;

    @Mock
    private ServerHttpResponse response;

    private final HttpHeaders httpHeaders = new HttpHeaders();

    @BeforeEach
    public void setUp() {
        lenient().when(exchange.getRequest()).thenReturn(request);
        lenient().when(request.getHeaders()).thenReturn(httpHeaders);
    }

    @Test
    public void testFilter_WithoutTenantId_BypassesFilter() {
        httpHeaders.clear();
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        Mono<Void> result = rateLimitFilter.filter(exchange, chain);

        StepVerifier.create(result)
                .verifyComplete();

        verify(chain, times(1)).filter(exchange);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    public void testFilter_WithinRateLimit_AllowsRequest() {
        String tenantId = "acme";
        String planCacheKey = "tenant:" + tenantId;

        httpHeaders.set("X-Tenant-ID", tenantId);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(planCacheKey)).thenReturn(Mono.just("{\"plan\":\"FREE\"}"));
        when(valueOperations.increment(anyString())).thenReturn(Mono.just(5L)); // 5 requests is under limit of 10
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        Mono<Void> result = rateLimitFilter.filter(exchange, chain);

        StepVerifier.create(result)
                .verifyComplete();

        verify(chain, times(1)).filter(exchange);
    }

    @Test
    public void testFilter_FirstRequest_SetsExpiry() {
        String tenantId = "acme";
        String planCacheKey = "tenant:" + tenantId;

        httpHeaders.set("X-Tenant-ID", tenantId);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(planCacheKey)).thenReturn(Mono.just("{\"plan\":\"FREE\"}"));
        when(valueOperations.increment(anyString())).thenReturn(Mono.just(1L)); // 1st request
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        Mono<Void> result = rateLimitFilter.filter(exchange, chain);

        StepVerifier.create(result)
                .verifyComplete();

        verify(redisTemplate, times(1)).expire(anyString(), eq(Duration.ofSeconds(60)));
        verify(chain, times(1)).filter(exchange);
    }

    @Test
    public void testFilter_ExceedsRateLimit_ReturnsTooManyRequests() {
        String tenantId = "acme";
        String planCacheKey = "tenant:" + tenantId;

        httpHeaders.set("X-Tenant-ID", tenantId);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(planCacheKey)).thenReturn(Mono.just("{\"plan\":\"FREE\"}"));
        when(valueOperations.increment(anyString())).thenReturn(Mono.just(11L)); // 11 requests exceeds limit of 10

        when(exchange.getResponse()).thenReturn(response);
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        when(response.bufferFactory()).thenReturn(org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance);
        when(response.writeWith(any())).thenReturn(Mono.empty());

        Mono<Void> result = rateLimitFilter.filter(exchange, chain);

        StepVerifier.create(result)
                .verifyComplete();

        verify(response, times(1)).setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        verify(chain, never()).filter(exchange);
    }
}
