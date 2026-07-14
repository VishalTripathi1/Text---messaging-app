package com.messaging.presentation.controller;

import com.messaging.application.service.UserService;
import com.messaging.domain.dto.request.UserProfileUpdateRequest;
import com.messaging.domain.dto.request.UserSettingsUpdateRequest;
import com.messaging.domain.dto.response.UserResponse;
import com.messaging.domain.dto.response.UserSettingsResponse;
import com.messaging.infrastructure.security.CustomUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * UserController - REST endpoints for fetching contacts, managing profiles, and user settings
 */
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User profile and settings management endpoints")
public class UserController {

    private final UserService userService;

    @GetMapping("/all")
    @Operation(summary = "Get all users", description = "Get a list of all registered users in the system")
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @GetMapping("/search")
    @Operation(summary = "Search users", description = "Search users by username or email query")
    public ResponseEntity<List<UserResponse>> searchUsers(@RequestParam("query") String query) {
        return ResponseEntity.ok(userService.searchUsers(query));
    }

    @GetMapping("/me")
    @Operation(summary = "Get current user info", description = "Fetch profile details of the currently logged-in user")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal CustomUserPrincipal principal) {
        return ResponseEntity.ok(userService.getCurrentUser(principal.getId()));
    }

    @PutMapping("/profile")
    @Operation(summary = "Update user profile", description = "Update display name, bio, and profile picture of the current user")
    public ResponseEntity<UserResponse> updateProfile(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody UserProfileUpdateRequest request
    ) {
        return ResponseEntity.ok(userService.updateProfile(principal.getId(), request));
    }

    @GetMapping("/settings")
    @Operation(summary = "Get user settings", description = "Retrieve theme and notifications preferences of the current user")
    public ResponseEntity<UserSettingsResponse> getSettings(@AuthenticationPrincipal CustomUserPrincipal principal) {
        return ResponseEntity.ok(userService.getSettings(principal.getId()));
    }

    @PutMapping("/settings")
    @Operation(summary = "Update user settings", description = "Update theme and notifications preferences of the current user")
    public ResponseEntity<UserSettingsResponse> updateSettings(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody UserSettingsUpdateRequest request
    ) {
        return ResponseEntity.ok(userService.updateSettings(principal.getId(), request));
    }
}
