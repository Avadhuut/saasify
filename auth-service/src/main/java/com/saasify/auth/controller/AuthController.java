package com.saasify.auth.controller;

import com.saasify.auth.dto.AuthResponse;
import com.saasify.auth.dto.LoginRequest;
import com.saasify.auth.dto.RegisterRequest;
import com.saasify.auth.entity.User;
import com.saasify.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller exposing endpoints for tenant registration, login, and session invalidation (logout).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    /**
     * Endpoint to register a user within the database schema of the active tenant.
     * Exposes POST /api/auth/register.
     * Required header: 'X-Tenant-ID' (validated and bound by TenantInterceptor).
     *
     * @param request user registration payload
     * @return the created user details
     */
    @PostMapping("/register")
    public ResponseEntity<User> register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    /**
     * Endpoint to authenticate a user inside the tenant context database schema.
     * Exposes POST /api/auth/login.
     * Required header: 'X-Tenant-ID' (validated and bound by TenantInterceptor).
     *
     * @param request the login request payload
     * @return response holding JWT access token and session metadata
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Endpoint to invalidate the user's refresh token and end their active session.
     * Exposes POST /api/auth/logout.
     * Required header: 'X-Tenant-ID' (for routing context validation) and 'Authorization: Bearer <token>'
     *
     * @param authHeader the Authorization request header
     * @return response with no content
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String authHeader) {
        authService.logout(authHeader);
        return ResponseEntity.ok().build();
    }
}
