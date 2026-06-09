package com.saasify.auth.service;

import com.saasify.auth.config.TenantContext;
import com.saasify.auth.dto.AuthResponse;
import com.saasify.auth.dto.LoginRequest;
import com.saasify.auth.dto.RegisterRequest;
import com.saasify.auth.entity.User;
import com.saasify.auth.repository.UserRepository;
import com.saasify.auth.security.JwtUtil;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;

/**
 * Service that coordinates user onboarding, credential verification, access token signing,
 * and refresh token lifecycle persistence in Redis.
 */
@Service
public class AuthService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private DataSource masterDataSource;

    /**
     * Registers a new user inside the current tenant's database schema.
     *
     * @param request user credentials
     * @return the created User entity
     */
    @Transactional
    public User register(RegisterRequest request) {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant context not initialized.");
        }

        // Verify if user already exists in this tenant schema
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You have already registered. Please try to log in.");
        }

        User user = User.builder()
                .id(UUID.randomUUID().toString())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole() != null ? request.getRole() : "MEMBER")
                .isActive(true)
                .build();

        return userRepository.save(user);
    }

    /**
     * Authenticates a user within the active tenant schema context.
     * Generates a signed access token and registers a 7-day refresh token in Redis.
     *
     * @param request the login request payload
     * @return the authentication response
     */
    public AuthResponse login(LoginRequest request) {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant context not initialized.");
        }

        // 1. Fetch user from tenant database schema
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password."));

        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is inactive.");
        }

        // 2. Validate password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }

        // 3. Look up subscription plan inside saasify_master
        String plan = queryTenantPlanFromMaster(tenantId);

        // 4. Generate signed access token
        String accessToken = jwtUtil.generateToken(tenantId, user.getId(), user.getEmail(), user.getRole(), plan);

        // 5. Generate secure refresh token and save to Redis under refresh:{tenantId}:{userId} (7-day TTL)
        String refreshToken = UUID.randomUUID().toString();
        String redisKey = "refresh:" + tenantId + ":" + user.getId();
        redisTemplate.opsForValue().set(redisKey, refreshToken, Duration.ofDays(7));

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userId(user.getId())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }

    /**
     * Terminate user session. Deletes active refresh tokens from Redis.
     *
     * @param authorizationHeader the Bearer JWT token header
     */
    public void logout(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Authorization header.");
        }
        String accessToken = authorizationHeader.substring(7);
        try {
            Claims claims = jwtUtil.extractAllClaims(accessToken);
            String tenantId = claims.get("tenantId", String.class);
            String userId = claims.get("userId", String.class);
            
            String redisKey = "refresh:" + tenantId + ":" + userId;
            redisTemplate.delete(redisKey);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to parse token claims on logout: " + e.getMessage());
        }
    }

    /**
     * Helper to retrieve subscription plan metadata from master catalog using JDBC.
     */
    private String queryTenantPlanFromMaster(String subdomain) {
        String plan = "FREE";
        try (Connection connection = masterDataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT plan FROM tenants WHERE subdomain = ?")) {
            ps.setString(1, subdomain);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    plan = rs.getString("plan");
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to fetch tenant plan. Defaulting to FREE. Error: " + e.getMessage());
        }
        return plan;
    }
}
