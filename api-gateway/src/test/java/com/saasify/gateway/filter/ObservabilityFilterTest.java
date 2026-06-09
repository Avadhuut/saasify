package com.saasify.gateway.filter;

import com.saasify.gateway.metrics.TenantMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ObservabilityFilterTest {

    @InjectMocks
    private ObservabilityFilter observabilityFilter;

    @Mock
    private TenantMetrics tenantMetrics;

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
    private HttpHeaders httpHeaders;

    @Mock
    private ServerWebExchange.Builder exchangeBuilder;

    @BeforeEach
    public void setUp() {
        lenient().when(exchange.getRequest()).thenReturn(request);
        lenient().when(request.getHeaders()).thenReturn(httpHeaders);
        lenient().when(request.getPath()).thenReturn(org.springframework.http.server.RequestPath.parse("/", null));
        
        lenient().when(request.mutate()).thenReturn(requestBuilder);
        lenient().when(requestBuilder.header(anyString(), any())).thenReturn(requestBuilder);
        lenient().when(requestBuilder.build()).thenReturn(request);
        
        lenient().when(exchange.mutate()).thenReturn(exchangeBuilder);
        lenient().when(exchangeBuilder.request(any(ServerHttpRequest.class))).thenReturn(exchangeBuilder);
        lenient().when(exchangeBuilder.build()).thenReturn(exchange);
        
        lenient().when(exchange.getResponse()).thenReturn(response);
    }

    @Test
    public void testFilter_PropagatesContextAndTriggersMetrics() {
        when(httpHeaders.getFirst("X-Tenant-ID")).thenReturn("acme");
        when(httpHeaders.getFirst("X-Correlation-ID")).thenReturn("correlation-123");
        when(httpHeaders.getFirst("X-User-ID")).thenReturn("user-456");
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        Mono<Void> result = observabilityFilter.filter(exchange, chain);

        StepVerifier.create(result)
                .verifyComplete();

        // Verify that custom headers mutated
        verify(requestBuilder).header("X-Tenant-ID", "acme");
        verify(requestBuilder).header("X-Correlation-ID", "correlation-123");
        verify(requestBuilder).header("X-User-ID", "user-456");

        // Verify that metrics counters and latency registers got executed
        verify(tenantMetrics, times(1)).incrementRequestCount(eq("acme"), eq("/"), anyString());
        verify(tenantMetrics, times(1)).recordLatency(eq("acme"), eq("/"), anyDouble());
    }

    @Test
    public void testFilter_DefaultValuesIfMissing() {
        when(httpHeaders.getFirst("X-Tenant-ID")).thenReturn(null);
        when(httpHeaders.getFirst("X-Correlation-ID")).thenReturn(null);
        when(httpHeaders.getFirst("X-User-ID")).thenReturn(null);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());

        Mono<Void> result = observabilityFilter.filter(exchange, chain);

        StepVerifier.create(result)
                .verifyComplete();

        verify(requestBuilder).header(eq("X-Tenant-ID"), eq("SYSTEM"));
        verify(requestBuilder).header(eq("X-Correlation-ID"), anyString()); // Generates UUID
        verify(requestBuilder).header(eq("X-User-ID"), eq("ANONYMOUS"));
    }
}
