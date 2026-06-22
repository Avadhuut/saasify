package com.saasify.billing.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks real-time live daily API total out of Redis per active tenant.
 * Uses a MultiGauge that is populated dynamically via a background scheduler task.
 */
@Component
public class UsageMetrics {

    private final RedisOperations<String, String> redisTemplate;
    private final DataSource masterDataSource;
    private final MultiGauge usageGauge;

    public UsageMetrics(RedisOperations<String, String> redisTemplate, DataSource masterDataSource, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.masterDataSource = masterDataSource;
        this.usageGauge = MultiGauge.builder("saasify_api_usage_current")
                .description("Real-time live daily API usage total per tenant")
                .register(meterRegistry);
    }

    /**
     * Periodically queries MySQL for active tenants, fetches their daily usage from Redis,
     * and maps these counts to the MultiGauge metrics registry.
     */
    @Scheduled(fixedDelay = 15000) // Runs every 15 seconds
    public void updateUsageGauges() {
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        String today = LocalDate.now().toString();

        try (Connection connection = masterDataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT id, subdomain, plan FROM tenants WHERE status = 'ACTIVE'");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String tenantUuid = rs.getString("id");
                String subdomain = rs.getString("subdomain");
                String plan = rs.getString("plan");

                String redisKey = "usage:" + subdomain + ":" + today;
                String redisKeyById = "usage:" + tenantUuid + ":" + today;

                String countVal = redisTemplate.opsForValue().get(redisKey);
                if (countVal == null) {
                    countVal = redisTemplate.opsForValue().get(redisKeyById);
                }

                long apiCalls = 0;
                if (countVal != null) {
                    try {
                        apiCalls = Long.parseLong(countVal);
                    } catch (NumberFormatException e) {
                        // ignore parsing errors
                    }
                }

                rows.add(MultiGauge.Row.of(
                        Tags.of("tenant", subdomain, "plan", plan),
                        apiCalls
                ));
            }

            // Update registered gauges dynamically
            usageGauge.register(rows, true);

        } catch (Exception e) {
            System.err.println("Error updating custom billing usage gauges: " + e.getMessage());
        }
    }
}
