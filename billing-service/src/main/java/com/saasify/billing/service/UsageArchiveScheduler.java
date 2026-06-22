package com.saasify.billing.service;

import com.saasify.billing.entity.TenantUsageHistory;
import com.saasify.billing.repository.UsageHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Scheduler service that archives the previous day's Redis metrics counters
 * into the permanent database history table exactly at midnight, clearing out Redis keys.
 */
@Service
public class UsageArchiveScheduler {

    @Autowired
    private RedisOperations<String, String> redisTemplate;

    @Autowired
    private UsageHistoryRepository usageHistoryRepository;

    @Autowired
    private DataSource masterDataSource;

    /**
     * Scheduled job running exactly at Midnight UTC.
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void archiveYesterdayUsage() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        String yesterdayStr = yesterday.toString();
        
        System.out.println("Starting daily usage archival task for date: " + yesterdayStr);

        try (Connection connection = masterDataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT id, subdomain FROM tenants WHERE status = 'ACTIVE'");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String tenantUuid = rs.getString("id");
                String subdomain = rs.getString("subdomain");

                // Check both ID and subdomain key formats to be safe
                String redisKey = "usage:" + subdomain + ":" + yesterdayStr;
                String redisKeyById = "usage:" + tenantUuid + ":" + yesterdayStr;

                long apiCalls = 0;
                String countVal = redisTemplate.opsForValue().get(redisKey);
                if (countVal == null) {
                    countVal = redisTemplate.opsForValue().get(redisKeyById);
                    redisKey = redisKeyById; // Update target delete key
                }

                if (countVal != null) {
                    try {
                        apiCalls = Long.parseLong(countVal);
                    } catch (NumberFormatException e) {
                        System.err.println("Warning: Invalid count format for tenant " + subdomain + ": " + countVal);
                    }
                }

                // Persist records into saasify_master history table (even if usage was 0)
                TenantUsageHistory history = TenantUsageHistory.builder()
                        .id(UUID.randomUUID().toString())
                        .tenantId(tenantUuid)
                        .date(yesterday)
                        .apiCalls(apiCalls)
                        .build();

                usageHistoryRepository.save(history);

                // Clean up Redis to prevent memory bloat
                redisTemplate.delete(redisKey);
            }
            System.out.println("Completed daily usage archival task.");

        } catch (Exception e) {
            System.err.println("Error executing midnight usage archival scheduler: " + e.getMessage());
        }
    }
}
