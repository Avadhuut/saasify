package com.saasify.tenant.repository;

import com.saasify.tenant.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Repository interface for managing transaction outbox event logging data.
 */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, String> {

    /**
     * Finds all unprocessed outbox events ordered by creation timestamp.
     */
    List<OutboxEvent> findByProcessedFalseOrderByCreatedAtAsc();
}
