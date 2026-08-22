package com.messaging.application.service;

import com.messaging.domain.dto.request.UserProfileUpdateRequest;
import com.messaging.domain.dto.request.UserSettingsUpdateRequest;
import com.messaging.domain.dto.response.UserResponse;
import com.messaging.domain.dto.response.UserSettingsResponse;
import com.messaging.domain.entity.User;
import com.messaging.domain.entity.UserProfile;
import com.messaging.domain.entity.UserSettings;
import com.messaging.infrastructure.repository.UserProfileRepository;
import com.messaging.infrastructure.repository.UserRepository;
import com.messaging.infrastructure.repository.UserSettingsRepository;
import com.messaging.presentation.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

import com.messaging.domain.dto.request.ContactSyncRequest;

/**
 * UserServiceImpl - Implementation of UserService for profile and settings updates
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserSettingsRepository userSettingsRepository;

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::mapToUserResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> searchUsers(String query) {
        return userRepository.searchByQuery(query).stream()
                .map(this::mapToUserResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> syncContacts(Long currentUserId, ContactSyncRequest request) {
        if (request == null || request.getPhoneNumbers() == null || request.getPhoneNumbers().isEmpty()) {
            return List.of();
        }

        List<String> normalizedList = request.getPhoneNumbers().stream()
                .filter(p -> p != null && !p.trim().isEmpty())
                .map(p -> p.replaceAll("[^0-9]", ""))
                .filter(p -> !p.isEmpty())
                .distinct()
                .collect(Collectors.toList());

        if (normalizedList.isEmpty()) {
            return List.of();
        }

        // Real indexed lookup instead of loading every user row into memory to filter. Phone
        // numbers are normalized to digits-only at registration (see AuthServiceImpl), so an
        // exact match against the normalized input list is correct and fast.
        return userRepository.findByPhoneNumberIn(normalizedList).stream()
                .filter(u -> !u.getId().equals(currentUserId))
                .map(this::mapToUserResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        return mapToUserResponse(user);
    }

    @Override
    @Transactional
    public UserResponse updateProfile(Long userId, UserProfileUpdateRequest request) {
        log.info("Updating profile for userId: {}", userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        UserProfile profile = userProfileRepository.findByUserId(userId)
                .orElseGet(() -> UserProfile.builder()
                        .user(user)
                        .status(UserProfile.UserStatus.ONLINE)
                        .isVerified(false)
                        .build());

        if (request.getDisplayName() != null) {
            profile.setDisplayName(request.getDisplayName());
        }
        if (request.getBio() != null) {
            profile.setBio(request.getBio());
        }
        if (request.getProfilePictureUrl() != null) {
            profile.setProfilePictureUrl(request.getProfilePictureUrl());
        }

        UserProfile savedProfile = userProfileRepository.save(profile);
        return mapToUserResponse(user, savedProfile);
    }

    @Override
    @Transactional
    public UserSettingsResponse getSettings(Long userId) {
        // Settings were previously only ever created lazily inside updateSettings(). A brand
        // new user who opens the Settings screen without having changed anything yet would hit
        // this endpoint first and get a 404 before any settings row existed - crashing a screen
        // that should just show sensible defaults. Creating (and immediately persisting) a
        // default row here, the same way updateSettings() already does, fixes that.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        UserSettings settings = userSettingsRepository.findByUserId(userId)
                .orElseGet(() -> userSettingsRepository.save(
                        UserSettings.builder().user(user).build()
                ));
        return mapToSettingsResponse(settings);
    }

    @Override
    @Transactional
    public UserSettingsResponse updateSettings(Long userId, UserSettingsUpdateRequest request) {
        log.info("Updating settings for userId: {}", userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        UserSettings settings = userSettingsRepository.findByUserId(userId)
                .orElseGet(() -> UserSettings.builder()
                        .user(user)
                        .build());

        if (request.getTheme() != null) {
            settings.setTheme(request.getTheme());
        }
        if (request.getNotificationsEnabled() != null) {
            settings.setNotificationsEnabled(request.getNotificationsEnabled());
        }
        if (request.getSoundEnabled() != null) {
            settings.setSoundEnabled(request.getSoundEnabled());
        }
        if (request.getVibrationEnabled() != null) {
            settings.setVibrationEnabled(request.getVibrationEnabled());
        }
        if (request.getShowReadReceipts() != null) {
            settings.setShowReadReceipts(request.getShowReadReceipts());
        }
        if (request.getShowTypingIndicator() != null) {
            settings.setShowTypingIndicator(request.getShowTypingIndicator());
        }

        UserSettings savedSettings = userSettingsRepository.save(settings);
        return mapToSettingsResponse(savedSettings);
    }

    private UserResponse mapToUserResponse(User user) {
        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElse(null);
        return mapToUserResponse(user, profile);
    }

    private UserResponse mapToUserResponse(User user, UserProfile profile) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .displayName(profile != null ? profile.getDisplayName() : user.getUsername())
                .bio(profile != null ? profile.getBio() : null)
                .profilePictureUrl(profile != null ? profile.getProfilePictureUrl() : null)
                .status(profile != null && profile.getStatus() != null ? profile.getStatus().name() : "OFFLINE")
                .lastSeen(profile != null ? profile.getLastSeen() : null)
                .isVerified(profile != null ? profile.getIsVerified() : false)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    private UserSettingsResponse mapToSettingsResponse(UserSettings settings) {
        return UserSettingsResponse.builder()
                .id(settings.getId())
                .userId(settings.getUser().getId())
                .theme(settings.getTheme())
                .notificationsEnabled(settings.getNotificationsEnabled())
                .soundEnabled(settings.getSoundEnabled())
                .vibrationEnabled(settings.getVibrationEnabled())
                .showReadReceipts(settings.getShowReadReceipts())
                .showTypingIndicator(settings.getShowTypingIndicator())
                .build();
    }
}
