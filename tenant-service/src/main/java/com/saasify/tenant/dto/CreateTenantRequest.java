package com.saasify.tenant.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body payload for registering a new tenant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTenantRequest {

    @NotBlank(message = "Tenant name is required")
    @Size(min = 2, max = 100, message = "Tenant name must be between 2 and 100 characters")
    private String name;

    @NotBlank(message = "Subdomain is required")
    @Size(min = 3, max = 50, message = "Subdomain must be between 3 and 50 characters")
    @Pattern(regexp = "^[a-z0-9-]+$", message = "Subdomain can only contain lowercase alphanumeric characters and hyphens")
    private String subdomain;

    @NotBlank(message = "Plan is required")
    @Pattern(regexp = "^(FREE|PRO|ENTERPRISE)$", message = "Plan must be FREE, PRO, or ENTERPRISE")
    private String plan; // FREE, PRO, ENTERPRISE

    @NotBlank(message = "Contact email is required")
    @Email(message = "Must be a well-formed email address")
    private String contactEmail;
}
