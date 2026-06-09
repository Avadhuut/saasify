package com.saasify.user.repository;

import com.saasify.user.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * Repository interface for executing database operations on the active tenant's isolated 'users' schema.
 */
@Repository
public interface UserRepository extends JpaRepository<AppUser, String> {

    /**
     * Finds a user by their email address.
     *
     * @param email the email to find
     * @return an Optional holding the AppUser
     */
    Optional<AppUser> findByEmail(String email);

    /**
     * Counts the total number of active users within the current tenant database schema context.
     * This count is critical for evaluating active subscription quotas.
     *
     * @return the count of active users
     */
    long countByIsActiveTrue();
}
