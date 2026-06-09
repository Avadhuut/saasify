package com.saasify.auth.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import io.opentelemetry.api.trace.Span;

import java.io.IOException;

/**
 * Servlet Filter that extracts gateway-injected telemetry headers and propagates
 * them to the local SLF4J MDC context and OpenTelemetry span attributes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcPropagationFilter implements Filter {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String USER_HEADER = "X-User-ID";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest) {
            HttpServletRequest httpRequest = (HttpServletRequest) request;
            String tenantId = httpRequest.getHeader(TENANT_HEADER);
            String correlationId = httpRequest.getHeader(CORRELATION_HEADER);
            String userId = httpRequest.getHeader(USER_HEADER);

            // Populate slf4j MDC context mapping
            if (tenantId != null && !tenantId.trim().isEmpty()) {
                MDC.put("tenantId", tenantId);
                Span.current().setAttribute("tenant.id", tenantId);
            }
            if (correlationId != null && !correlationId.trim().isEmpty()) {
                MDC.put("correlationId", correlationId);
                Span.current().setAttribute("correlation.id", correlationId);
            }
            if (userId != null && !userId.trim().isEmpty()) {
                MDC.put("userId", userId);
                Span.current().setAttribute("user.id", userId);
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // Guarantee context cleanup
            MDC.clear();
        }
    }
}
