package com.saasify.tenant.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * JPA Entity mapping to the 'tenants' administrative table in the master database.
 */
@Entity
@Table(name = "tenants")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 50)
    private String subdomain;

    @Column(nullable = false, length = 20)
    private String status; // ACTIVE, SUSPENDED, DELETED

    @Column(nullable = false, length = 20)
    private String plan; // FREE, PRO, ENTERPRISE

    @Column(name = "schema_name", nullable = false, length = 100)
    private String schemaName;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
