package com.saasify.billing.repository;

import com.saasify.billing.entity.TenantUsageHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Repository interface for executing database operations on the 'tenant_usage_history' master table.
 */
@Repository
public interface UsageHistoryRepository extends JpaRepository<TenantUsageHistory, String> {

    /**
     * Retrieves usage log history for a specific tenant ordered by date descending.
     *
     * @param tenantId the tenant UUID
     * @return the list of historical records
     */
    List<TenantUsageHistory> findByTenantIdOrderByDateDesc(String tenantId);
}
