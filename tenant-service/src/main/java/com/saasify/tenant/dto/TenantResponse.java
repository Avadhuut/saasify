package com.saasify.tenant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * Response payload returned after successfully onboarding or retrieving a tenant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantResponse {
    private String id;
    private String name;
    private String subdomain;
    private String status;
    private String plan;
    private String schemaName;
    private String contactEmail;
    private LocalDateTime createdAt;
}
