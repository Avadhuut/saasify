package com.saasify.billing.controller;

import com.saasify.billing.entity.TenantUsageHistory;
import com.saasify.billing.repository.UsageHistoryRepository;
import com.saasify.billing.service.UsageArchiveScheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller exposing billing metrics lookup REST endpoints for dashboards.
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    @Autowired
    private RedisOperations<String, String> redisTemplate;

    @Autowired
    private UsageHistoryRepository usageHistoryRepository;

    @Autowired
    private UsageArchiveScheduler usageArchiveScheduler;

    @Autowired
    private DataSource dataSource;

    /**
     * Resolves tenant UUID and subdomain from the master database.
     */
    private Map<String, String> getTenantDetails(String tenantId) {
        Map<String, String> details = new HashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT id, subdomain FROM tenants WHERE subdomain = ? OR id = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    details.put("id", rs.getString("id"));
                    details.put("subdomain", rs.getString("subdomain"));
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to query tenant details: " + e.getMessage());
        }
        return details;
    }

    /**
     * Retrieves the active daily API call counts out of Redis.
     * Exposes GET /api/billing/usage/{tenantId}.
     *
     * @param tenantId the tenant subdomain or UUID
     */
    @GetMapping("/usage/{tenantId}")
    public ResponseEntity<Map<String, Object>> getCurrentUsage(@PathVariable String tenantId) {
        String today = LocalDate.now().toString();
        Map<String, String> details = getTenantDetails(tenantId);
        String subdomain = details.getOrDefault("subdomain", tenantId);
        
        String redisKey = "usage:" + subdomain + ":" + today;
        
        String countVal = redisTemplate.opsForValue().get(redisKey);
        long apiCalls = 0;
        
        if (countVal != null) {
            try {
                apiCalls = Long.parseLong(countVal);
            } catch (NumberFormatException e) {
                // Ignore parsing errors and default to 0
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("tenantId", tenantId);
        response.put("date", today);
        response.put("apiCalls", apiCalls);

        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves historical archived telemetry reports from the master database.
     * Exposes GET /api/billing/usage/{tenantId}/history.
     *
     * @param tenantId the tenant UUID or subdomain
     */
    @GetMapping("/usage/{tenantId}/history")
    public ResponseEntity<List<TenantUsageHistory>> getUsageHistory(@PathVariable String tenantId) {
        Map<String, String> details = getTenantDetails(tenantId);
        String tenantUuid = details.getOrDefault("id", tenantId);
        List<TenantUsageHistory> history = usageHistoryRepository.findByTenantIdOrderByDateDesc(tenantUuid);
        return ResponseEntity.ok(history);
    }

    /**
     * Debug endpoint to manually trigger the midnight usage archive scheduler.
     * Exposes POST /api/billing/usage/trigger-archive.
     */
    @PostMapping("/usage/trigger-archive")
    public ResponseEntity<Map<String, String>> triggerArchive() {
        usageArchiveScheduler.archiveYesterdayUsage();
        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", "Yesterday's Redis usage metrics have been archived to the database history.");
        return ResponseEntity.ok(response);
    }
}


