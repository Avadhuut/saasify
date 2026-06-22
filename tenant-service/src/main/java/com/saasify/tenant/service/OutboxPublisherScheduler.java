package com.saasify.tenant.service;

import com.saasify.tenant.model.OutboxEvent;
import com.saasify.tenant.repository.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Background scheduler that pulls unprocessed outbox events from the database
 * and publishes them safely to Kafka, achieving at-least-once delivery guarantees.
 */
@Service
public class OutboxPublisherScheduler {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired(required = false)
    private KafkaTemplate<String, String> kafkaTemplate;

    /**
     * Periodically processes outbox records every 5 seconds.
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxRepository.findByProcessedFalseOrderByCreatedAtAsc();
        if (pendingEvents.isEmpty()) {
            return;
        }

        System.out.println("Outbox: Processing " + pendingEvents.size() + " pending events...");

        for (OutboxEvent event : pendingEvents) {
            boolean success = false;
            try {
                if (kafkaTemplate != null) {
                    // Route to the appropriate Kafka topic based on the eventType
                    String topic = resolveTopic(event.getEventType());
                    if (topic != null) {
                        System.out.println("Outbox: Publishing event " + event.getId() + " to topic " + topic);
                        kafkaTemplate.send(topic, event.getPayload()).get(); // Synchronous get to verify success
                        success = true;
                    } else {
                        System.err.println("Outbox: Unknown event type: " + event.getEventType());
                        // Mark as processed anyway to avoid blocking the outbox pipeline on invalid events
                        success = true;
                    }
                } else {
                    System.err.println("Outbox Warning: KafkaTemplate is missing. Retrying later.");
                }
            } catch (Exception e) {
                System.err.println("Outbox Error: Failed to publish event " + event.getId() + ". Error: " + e.getMessage());
            }

            if (success) {
                event.setProcessed(true);
                outboxRepository.save(event);
            }
        }
    }

    private String resolveTopic(String eventType) {
        if ("TENANT_SUSPENDED".equalsIgnoreCase(eventType)) {
            return "tenant.suspended";
        } else if ("TENANT_CREATED".equalsIgnoreCase(eventType)) {
            return "tenant.created";
        } else if ("TENANT_PLAN_UPGRADED".equalsIgnoreCase(eventType)) {
            return "tenant.plan.upgraded";
        }
        return null;
    }
}
