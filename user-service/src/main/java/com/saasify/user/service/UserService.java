package com.saasify.user.service;

import com.saasify.user.config.TenantContext;
import com.saasify.user.dto.CreateUserRequest;
import com.saasify.user.dto.TenantResponse;
import com.saasify.user.dto.UpdateUserRequest;
import com.saasify.user.entity.AppUser;
import com.saasify.user.exception.QuotaExceededException;
import com.saasify.user.feign.TenantServiceClient;
import com.saasify.user.repository.UserRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Service that handles user management, evaluates plan quotas dynamically,
 * calls tenant-service through a resilient Feign Client, and processes fallback defaults.
 */
@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TenantServiceClient tenantServiceClient;

    @Autowired
    private DataSource masterDataSource;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * Retrieves all users within the current tenant database context.
     */
    public List<AppUser> getAllUsers() {
        return userRepository.findAll();
    }

    /**
     * Retrieves a user by their UUID.
     */
    public AppUser getUserById(String id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found with ID: " + id));
    }

    /**
     * Updates an existing user inside the current tenant schema.
     */
    @Transactional
    public AppUser updateUser(String id, UpdateUserRequest request) {
        AppUser user = getUserById(id);
        if (request.getRole() != null) {
            user.setRole(request.getRole());
        }
        if (request.getIsActive() != null) {
            user.setActive(request.getIsActive());
        }
        return userRepository.save(user);
    }

    /**
     * Deletes a user by ID.
     */
    @Transactional
    public void deleteUser(String id) {
        AppUser user = getUserById(id);
        userRepository.delete(user);
    }

    /**
     * Registers a new user within the tenant's private database schema after performing quota evaluations.
     *
     * @param request the registration details
     * @return the saved AppUser
     */
    @Transactional
    public AppUser createUser(CreateUserRequest request) {
        String subdomain = TenantContext.getCurrentTenant();
        if (subdomain == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant context not initialized.");
        }

        // 1. Check if user already exists
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User with email '" + request.getEmail() + "' already exists.");
        }

        // 2. Count active users inside this isolated tenant database
        long activeCount = userRepository.countByIsActiveTrue();

        // 3. Resolve tenant UUID from saasify_master
        String tenantUuid = queryTenantIdFromMaster(subdomain);

        // 4. Fetch tenant registration parameters resiliently using Feign client
        TenantResponse tenant = getTenantResilient(tenantUuid);
        String plan = tenant.getPlan();

        // 5. Evaluate thresholds
        long limit = getPlanUserLimit(plan);
        if (limit > 0 && activeCount >= limit) {
            throw new QuotaExceededException(activeCount, limit, plan, "https://saasify.com/upgrade");
        }

        // 6. Save the user record
        AppUser user = AppUser.builder()
                .id(UUID.randomUUID().toString())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole() != null ? request.getRole() : "MEMBER")
                .isActive(true)
                .build();

        return userRepository.save(user);
    }

    /**
     * Fetches tenant details through Feign client, guarded by Resilience4j Circuit Breaker & Retry.
     */
    @CircuitBreaker(name = "tenantService", fallbackMethod = "tenantServiceFallback")
    @Retry(name = "tenantService")
    public TenantResponse getTenantResilient(String tenantId) {
        return tenantServiceClient.getTenantById(tenantId);
    }

    /**
     * Fallback method triggered when tenant-service is unavailable.
     * Returns a safe default plan threshold to prevent cascading failures.
     */
    public TenantResponse tenantServiceFallback(String tenantId, Throwable t) {
        System.err.println("Warning: tenant-service is down. Falling back to default plan limits. Reason: " + t.getMessage());
        return TenantResponse.builder()
                .id(tenantId)
                .name("Fallback Tenant")
                .subdomain(TenantContext.getCurrentTenant())
                .status("ACTIVE")
                .plan("PRO") // Falls back to PRO plan (limit 50) to allow normal operation for active users
                .build();
    }

    private long getPlanUserLimit(String plan) {
        if ("FREE".equalsIgnoreCase(plan)) {
            return 5;
        } else if ("PRO".equalsIgnoreCase(plan)) {
            return 50;
        }
        return -1; // Unlimited for ENTERPRISE or other plans
    }

    private String queryTenantIdFromMaster(String subdomain) {
        try (Connection connection = masterDataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT id FROM tenants WHERE subdomain = ?")) {
            ps.setString(1, subdomain);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("id");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to lookup tenant ID for subdomain: " + subdomain, e);
        }
        throw new IllegalArgumentException("No tenant registered for subdomain: " + subdomain);
    }

}
