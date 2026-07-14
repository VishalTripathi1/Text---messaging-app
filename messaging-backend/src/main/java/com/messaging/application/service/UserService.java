package com.messaging.application.service;

import com.messaging.domain.dto.request.UserProfileUpdateRequest;
import com.messaging.domain.dto.request.UserSettingsUpdateRequest;
import com.messaging.domain.dto.response.UserResponse;
import com.messaging.domain.dto.response.UserSettingsResponse;

import java.util.List;

/**
 * UserService - Exposes user retrieval, profile updates, and settings configuration
 */
public interface UserService {
    List<UserResponse> getAllUsers();
    List<UserResponse> searchUsers(String query);
    UserResponse getCurrentUser(Long userId);
    UserResponse updateProfile(Long userId, UserProfileUpdateRequest request);
    UserSettingsResponse getSettings(Long userId);
    UserSettingsResponse updateSettings(Long userId, UserSettingsUpdateRequest request);
}
