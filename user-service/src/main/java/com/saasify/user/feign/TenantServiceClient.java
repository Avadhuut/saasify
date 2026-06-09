package com.saasify.user.feign;

import com.saasify.user.config.FeignConfig;
import com.saasify.user.dto.TenantResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * OpenFeign declarative client mapping request paths onto the active 'tenant-service' discovery registry.
 */
@FeignClient(name = "tenant-service", configuration = FeignConfig.class)
public interface TenantServiceClient {

    /**
     * Queries tenant parameter metadata from tenant-service by ID.
     * Maps GET /api/tenants/{id}.
     *
     * @param id the tenant UUID
     * @return the resolved tenant parameters
     */
    @GetMapping("/api/tenants/{id}")
    TenantResponse getTenantById(@PathVariable("id") String id);
}
