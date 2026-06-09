package com.saasify.user.controller;

import com.saasify.user.dto.CreateUserRequest;
import com.saasify.user.dto.UpdateUserRequest;
import com.saasify.user.dto.UserResponse;
import com.saasify.user.entity.AppUser;
import com.saasify.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Controller exposing REST endpoints to execute user administration actions within active tenant scopes.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * Endpoint to retrieve all users for the current tenant.
     * Exposes GET /api/users.
     */
    @GetMapping
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        List<AppUser> users = userService.getAllUsers();
        List<UserResponse> responses = users.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    /**
     * Endpoint to fetch a single user by ID.
     * Exposes GET /api/users/{id}.
     */
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable String id) {
        AppUser user = userService.getUserById(id);
        return ResponseEntity.ok(mapToResponse(user));
    }

    /**
     * Endpoint to register a user within the active tenant, performing limit checks first.
     * Exposes POST /api/users.
     */
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        AppUser user = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(user));
    }

    /**
     * Endpoint to update user metadata inside the tenant scope.
     * Exposes PUT /api/users/{id}.
     */
    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(@PathVariable String id, @RequestBody UpdateUserRequest request) {
        AppUser user = userService.updateUser(id, request);
        return ResponseEntity.ok(mapToResponse(user));
    }

    /**
     * Endpoint to delete/deprovision a user.
     * Exposes DELETE /api/users/{id}.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable String id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    private UserResponse mapToResponse(AppUser user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(user.getRole())
                .isActive(user.isActive())
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt() : java.time.LocalDateTime.now())
                .build();
    }
}
