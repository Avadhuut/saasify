package com.saasify.user.config;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class that maps request interception behaviors onto active OpenFeign client pipelines.
 */
@Configuration
public class FeignConfig {

    private static final String TENANT_HEADER = "X-Tenant-ID";

    /**
     * RequestInterceptor bean that copies the tenant context header from the incoming request's ThreadLocal context
     * and injects it into the outgoing Feign request template headers.
     */
    @Bean
    public RequestInterceptor tenantHeaderInterceptor() {
        return requestTemplate -> {
            String currentTenant = TenantContext.getCurrentTenant();
            if (currentTenant != null && !currentTenant.trim().isEmpty()) {
                requestTemplate.header(TENANT_HEADER, currentTenant);
            }
        };
    }
}
