package com.saasify.tenant.repository;

import com.saasify.tenant.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * Repository interface for executing database operations on the 'tenants' table.
 */
@Repository
public interface TenantRepository extends JpaRepository<Tenant, String> {

    /**
     * Looks up a tenant by their unique subdomain.
     *
     * @param subdomain the subdomain to search for
     * @return an Optional holding the Tenant if found
     */
    Optional<Tenant> findBySubdomain(String subdomain);

    /**
     * Checks if a tenant already exists with the given subdomain.
     *
     * @param subdomain the subdomain to verify
     * @return true if a tenant already exists
     */
    boolean existsBySubdomain(String subdomain);
}
