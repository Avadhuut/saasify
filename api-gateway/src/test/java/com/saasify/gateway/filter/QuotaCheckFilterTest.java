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

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class QuotaCheckFilterTest {

    @InjectMocks
    private QuotaCheckFilter quotaCheckFilter;

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

        Mono<Void> result = quotaCheckFilter.filter(exchange, chain);

        StepVerifier.create(result)
                .verifyComplete();

        verify(chain, times(1)).filter(exchange);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    public void testFilter_WithinQuotaLimit_AllowsRequest() {
        String tenantId = "acme";
        String today = LocalDate.now().toString();
        String planCacheKey = "tenant:" + tenantId;
        String usageKey = "usage:" + tenantId + ":" + today;

        httpHeaders.set("X-Tenant-ID", tenantId);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(planCacheKey)).thenReturn(Mono.just("{\"plan\":\"FREE\"}"));
        when(valueOperations.get(usageKey)).thenReturn(Mono.just("50"));
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        Mono<Void> result = quotaCheckFilter.filter(exchange, chain);

        StepVerifier.create(result)
                .verifyComplete();

        verify(chain, times(1)).filter(exchange);
    }

    @Test
    public void testFilter_ExceedsQuotaLimit_ReturnsTooManyRequests() {
        String tenantId = "acme";
        String today = LocalDate.now().toString();
        String planCacheKey = "tenant:" + tenantId;
        String usageKey = "usage:" + tenantId + ":" + today;

        httpHeaders.set("X-Tenant-ID", tenantId);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(planCacheKey)).thenReturn(Mono.just("{\"plan\":\"FREE\"}"));
        when(valueOperations.get(usageKey)).thenReturn(Mono.just("100")); // Limit for FREE is 100

        when(exchange.getResponse()).thenReturn(response);
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        when(response.bufferFactory()).thenReturn(org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance);
        when(response.writeWith(any())).thenReturn(Mono.empty());

        Mono<Void> result = quotaCheckFilter.filter(exchange, chain);

        StepVerifier.create(result)
                .verifyComplete();

        verify(response, times(1)).setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        verify(chain, never()).filter(exchange);
    }
}
