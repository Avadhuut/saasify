package com.saasify.auth.repository;

import com.saasify.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * Repository interface for managing queries on the tenant-specific 'users' schema.
 * Connection boundaries are handled dynamically by MultiTenantRoutingDataSource at runtime.
 */
@Repository
public interface UserRepository extends JpaRepository<User, String> {

    /**
     * Finds a user by their email. Since routing shifts database dynamically,
     * this query executes strictly inside the active tenant's schema.
     *
     * @param email the email to look up
     * @return an Optional holding the User
     */
    Optional<User> findByEmail(String email);
}
