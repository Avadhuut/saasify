package com.saasify.tenant.controller;

import com.saasify.tenant.dto.CreateTenantRequest;
import com.saasify.tenant.dto.TenantResponse;
import com.saasify.tenant.model.Tenant;
import com.saasify.tenant.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;



/**
 * Controller exposing REST endpoints to manage and onboard tenants.
 */
@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    @Autowired
    private TenantService tenantService;

    /**
     * Endpoint to fetch tenant parameters by ID.
     * Exposes GET /api/tenants/{id}.
     *
     * @param id the tenant UUID
     * @return the tenant response details
     */
    @GetMapping("/{id}")
    public ResponseEntity<TenantResponse> getTenantById(@PathVariable String id) {
        Tenant tenant = tenantService.getTenantById(id);
        
        TenantResponse response = TenantResponse.builder()
                .id(tenant.getId())
                .name(tenant.getName())
                .subdomain(tenant.getSubdomain())
                .status(tenant.getStatus())
                .plan(tenant.getPlan())
                .schemaName(tenant.getSchemaName())
                .contactEmail(tenant.getContactEmail())
                .createdAt(tenant.getCreatedAt() != null ? tenant.getCreatedAt() : java.time.LocalDateTime.now())
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Endpoint to onboard and provision a new tenant environment.
     * Exposes POST /api/tenants.
     *
     * @param request the registration details
     * @return a response with status 201 Created and the onboarded tenant details
     */
    @PostMapping
    public ResponseEntity<TenantResponse> createTenant(@Valid @RequestBody CreateTenantRequest request) {
        Tenant tenant = tenantService.createTenant(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(tenant));
    }

    /**
     * Endpoint to list all registered tenants.
     * Exposes GET /api/tenants.
     */
    @GetMapping
    public ResponseEntity<java.util.List<TenantResponse>> getAllTenants() {
        java.util.List<Tenant> tenants = tenantService.getAllTenants();
        java.util.List<TenantResponse> response = tenants.stream()
                .map(this::mapToResponse)
                .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(response);
    }

    /**
     * Endpoint to update the plan of a tenant.
     * Exposes PUT /api/tenants/{id}/plan?plan=PRO.
     */
    @PutMapping("/{id}/plan")
    public ResponseEntity<TenantResponse> updateTenantPlan(@PathVariable String id, @RequestParam String plan) {
        Tenant tenant = tenantService.updateTenantPlan(id, plan);
        return ResponseEntity.ok(mapToResponse(tenant));
    }

    /**
     * Endpoint to update the status of a tenant (e.g. SUSPENDED).
     * Exposes PUT /api/tenants/{id}/status?status=SUSPENDED.
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<TenantResponse> updateTenantStatus(@PathVariable String id, @RequestParam String status) {
        Tenant tenant = tenantService.updateTenantStatus(id, status);
        return ResponseEntity.ok(mapToResponse(tenant));
    }

    /**
     * Endpoint to soft delete a tenant.
     * Exposes DELETE /api/tenants/{id}.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<TenantResponse> deleteTenant(@PathVariable String id) {
        Tenant tenant = tenantService.softDeleteTenant(id);
        return ResponseEntity.ok(mapToResponse(tenant));
    }

    private TenantResponse mapToResponse(Tenant tenant) {
        return TenantResponse.builder()
                .id(tenant.getId())
                .name(tenant.getName())
                .subdomain(tenant.getSubdomain())
                .status(tenant.getStatus())
                .plan(tenant.getPlan())
                .schemaName(tenant.getSchemaName())
                .contactEmail(tenant.getContactEmail())
                .createdAt(tenant.getCreatedAt() != null ? tenant.getCreatedAt() : java.time.LocalDateTime.now())
                .build();
    }
}

