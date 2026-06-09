package com.saasify.auth.kafka;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import java.util.Set;

/**
 * Asynchronous Poison-Pill Consumer listening for tenant suspension events.
 * Evicts all active session refresh tokens from Redis for the suspended tenant,
 * forcing instant logout across the platform.
 */
@Service
public class TenantSuspendedConsumer {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private javax.sql.DataSource masterDataSource;

    /**
     * Listens for tenant suspension payloads. Scan and evict matching keys.
     * Automatically retries up to 3 times before routing to the DLT.
     *
     * @param payload the suspended tenant subdomain, UUID, or JSON payload
     */
    @RetryableTopic(
            attempts = "3",
            dltStrategy = DltStrategy.ALWAYS_RETRY_ON_ERROR,
            dltTopicSuffix = ".DLT"
    )
    @KafkaListener(topics = "tenant.suspended", groupId = "auth-suspended-group")
    public void consumeSuspendedTenant(@Payload String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            throw new IllegalArgumentException("Suspended tenant ID payload cannot be null or empty.");
        }

        System.out.println("Processing session eviction event for payload: " + payload);

        // 1. Parse JSON format if sent as JSON (Goal 4.3)
        String extractedId = payload.trim();
        if (extractedId.startsWith("{")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"tenantId\"\\s*:\\s*\"([^\"]+)\"").matcher(extractedId);
            if (m.find()) {
                extractedId = m.group(1);
            }
        }

        // 2. Resolve both subdomain and UUID from master catalog
        String subdomain = null;
        String uuid = null;

        try (java.sql.Connection conn = masterDataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement("SELECT id, subdomain FROM tenants WHERE id = ? OR subdomain = ?")) {
            ps.setString(1, extractedId);
            ps.setString(2, extractedId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    uuid = rs.getString("id");
                    subdomain = rs.getString("subdomain");
                }
            }
        } catch (Exception e) {
            System.err.println("Error looking up tenant in database: " + e.getMessage());
        }

        // 3. Fallback to using extractedId directly if database lookup yielded nothing
        if (subdomain == null && uuid == null) {
            subdomain = extractedId;
        }

        // 4. Perform evictions for both formats (UUID and subdomain)
        if (subdomain != null) {
            evictSessions(subdomain);
        }
        if (uuid != null && !uuid.equalsIgnoreCase(subdomain)) {
            evictSessions(uuid);
        }
    }

    private void evictSessions(String tenantKey) {
        String pattern = "refresh:" + tenantKey + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            System.out.println("Evicting " + keys.size() + " active sessions for pattern: " + pattern);
            redisTemplate.delete(keys);
        } else {
            System.out.println("No active user sessions found for pattern: " + pattern);
        }
    }

    /**
     * Handles messages that failed all retry attempts and routes them to a Dead-Letter Topic.
     */
    @DltHandler
    public void handleDlt(String payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        System.err.println("Tenant suspension event failed retry attempts and was routed to DLT. Topic: " + topic + ", Payload: " + payload);
    }
}
