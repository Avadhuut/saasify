package com.saasify.billing.kafka;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDate;

/**
 * Consumer that processes asynchronous api usage events, updates Redis metrics counters,
 * and publishes alerts when thresholds are reached.
 */
@Service
public class UsageEventConsumer {

    @Autowired
    private RedisOperations<String, String> redisTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private DataSource masterDataSource;

    /**
     * Consumes events from the 'usage.recorded' Kafka topic.
     * Increment the daily API counter in Redis and trigger alerts if quotas are hit.
     *
     * @param tenantId the subdomain or identifier of the tenant
     */
    @RetryableTopic(
            attempts = "4", // 1 initial attempt + 3 retries
            backoff = @Backoff(delay = 2000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_DELAY_VALUE,
            dltTopicSuffix = ".DLQ"
    )
    @KafkaListener(topics = "usage.recorded", groupId = "billing-usage-group")
    public void consumeUsageEvent(@Payload String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            return;
        }

        String today = LocalDate.now().toString();
        String redisKey = "usage:" + tenantId + ":" + today;

        // Atomically increment the daily count in Redis
        Long count = redisTemplate.opsForValue().increment(redisKey);

        if (count != null) {
            // If the key is newly created, apply absolute 48-hour TTL
            if (count == 1) {
                redisTemplate.expire(redisKey, Duration.ofHours(48));
            }

            // Look up plan limit dynamically from the master DB catalog
            String plan = queryTenantPlan(tenantId);
            long limit = getPlanQuota(plan);

            // If the quota limit is hit, trigger an alert payload
            if (limit > 0 && count == limit) {
                System.out.println("Quota limit hit for tenant: " + tenantId + " (Count: " + count + "). Dispatching warning event.");
                kafkaTemplate.send("quota.exceeded", tenantId);
            }
        }
    }

    /**
     * Dead Letter Queue (DLQ) handler for processing failures that exhausted all retries.
     */
    @DltHandler
    public void handleDltMessage(@Payload String tenantId, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        System.err.println("CRITICAL: Telemetry event for tenant '" + tenantId + "' routed to Dead Letter Queue (DLQ) from topic: " + topic);
    }

    /**
     * Resolves the subscription plan tier of the tenant by querying the master database.
     */
    private String queryTenantPlan(String tenantId) {
        String plan = "FREE";
        try (Connection connection = masterDataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT plan FROM tenants WHERE subdomain = ? OR id = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    plan = rs.getString("plan");
                }
            }
        } catch (Exception e) {
            System.err.println("Error: Usage consumer failed to query tenant plan. Throwing exception to trigger Kafka retry. Error: " + e.getMessage());
            throw new RuntimeException("Failed to query tenant plan from master database", e);
        }
        return plan;
    }

    private long getPlanQuota(String plan) {
        if ("FREE".equalsIgnoreCase(plan)) {
            return 100; // 100 API calls per day limit
        } else if ("PRO".equalsIgnoreCase(plan)) {
            return 10000; // 10,000 API calls per day limit
        }
        return -1; // Unlimited for ENTERPRISE
    }
}
