package com.saasify.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * JPA Entity mapping to the 'tenant_usage_history' table in the master database.
 * Tracks completed historical daily api usage parameters per tenant.
 */
@Entity
@Table(name = "tenant_usage_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantUsageHistory {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "api_calls", nullable = false)
    private long apiCalls;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
