package com.saasify.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * DTO matching responses returned from tenant-service queries.
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
    private String plan; // FREE, PRO, ENTERPRISE
    private String schemaName;
    private String contactEmail;
    private LocalDateTime createdAt;
}
