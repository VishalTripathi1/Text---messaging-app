package com.messaging.application.service;

import com.messaging.domain.dto.request.LoginRequest;
import com.messaging.domain.dto.request.RegisterRequest;
import com.messaging.domain.dto.response.AuthResponse;
import com.messaging.domain.entity.*;
import com.messaging.infrastructure.repository.*;
import com.messaging.infrastructure.security.JwtProvider;
import com.messaging.presentation.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * AuthServiceImpl
 *
 * Handles:
 *  - user registration
 *  - login
 *  - access-token refresh
 *  - token validation
 *  - logout
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final DeviceRepository deviceRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    @Override
    @Transactional
    public AuthResponse register(
            RegisterRequest request
    ) {

        log.info(
                "Registering new user: {}",
                request.getUsername()
        );

        if (
                userRepository.existsByUsername(
                        request.getUsername()
                )
        ) {
            throw new BadRequestException(
                    "Username is already taken"
            );
        }

        if (
                userRepository.existsByEmail(
                        request.getEmail()
                )
        ) {
            throw new BadRequestException(
                    "Email is already registered"
            );
        }

        // 1. Create User
        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .passwordHash(
                        passwordEncoder.encode(
                                request.getPassword()
                        )
                )
                .phoneNumber(
                        request.getPhoneNumber()
                )
                .isActive(true)
                .build();

        user = userRepository.save(user);

        // 2. Create UserProfile
        UserProfile profile =
                UserProfile.builder()
                        .user(user)
                        .displayName(
                                request.getDisplayName()
                        )
                        .status(
                                UserProfile.UserStatus.OFFLINE
                        )
                        .isVerified(false)
                        .build();

        userProfileRepository.save(profile);

        // 3. Create UserSettings
        UserSettings settings =
                UserSettings.builder()
                        .user(user)
                        .theme("LIGHT")
                        .notificationsEnabled(true)
                        .soundEnabled(true)
                        .vibrationEnabled(true)
                        .showReadReceipts(true)
                        .showTypingIndicator(true)
                        .build();

        userSettingsRepository.save(settings);

        // 4. Create default Device
        String defaultDeviceId =
                "reg_" +
                UUID.randomUUID()
                        .toString()
                        .substring(0, 8);

        Device device =
                Device.builder()
                        .user(user)
                        .deviceId(defaultDeviceId)
                        .deviceName(
                                "Default Registration Device"
                        )
                        .deviceType("UNKNOWN")
                        .isActive(true)
                        .lastActive(
                                LocalDateTime.now()
                        )
                        .build();

        deviceRepository.save(device);

        // 5. Generate tokens
        String accessToken =
                jwtProvider.generateAccessToken(
                        user.getId(),
                        user.getUsername()
                );

        String refreshToken =
                jwtProvider.generateRefreshToken(
                        user.getId(),
                        user.getUsername()
                );

        // 6. Create Session
        //
        // IMPORTANT:
        // Session lifetime must follow the refresh token,
        // NOT the short-lived access token.
        LocalDateTime expiryDate =
                LocalDateTime.now()
                        .plusNanos(
                                jwtProvider
                                        .getRefreshTokenExpirationTime()
                                        * 1_000_000
                        );

        Session session =
                Session.builder()
                        .user(user)
                        .device(device)
                        .token(accessToken)
                        .refreshToken(refreshToken)
                        .expiresAt(expiryDate)
                        .build();

        sessionRepository.save(session);

        return AuthResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(
                        jwtProvider
                                .getTokenExpirationTime()
                                / 1000
                )
                .build();
    }

    @Override
    @Transactional
    public AuthResponse login(
            LoginRequest request
    ) {

        log.info(
                "Logging in user: {}",
                request.getUsername()
        );

        User user =
                userRepository
                        .findByUsername(
                                request.getUsername()
                        )
                        .orElseThrow(
                                () ->
                                        new BadCredentialsException(
                                                "Invalid username or password"
                                        )
                        );

        if (!user.getIsActive()) {
            throw new BadRequestException(
                    "User account is deactivated"
            );
        }

        if (
                !passwordEncoder.matches(
                        request.getPassword(),
                        user.getPasswordHash()
                )
        ) {
            throw new BadCredentialsException(
                    "Invalid username or password"
            );
        }

        // 1. Get or create device
        Device device =
                deviceRepository
                        .findByDeviceId(
                                request.getDeviceId()
                        )
                        .orElseGet(
                                () ->
                                        Device.builder()
                                                .user(user)
                                                .deviceId(
                                                        request.getDeviceId()
                                                )
                                                .deviceName(
                                                        request.getDeviceName() != null
                                                                ? request.getDeviceName()
                                                                : "Unknown Device"
                                                )
                                                .deviceType("ANDROID")
                                                .isActive(true)
                                                .build()
                        );

        device.setLastActive(
                LocalDateTime.now()
        );

        device.setIsActive(true);

        device =
                deviceRepository.save(device);

        // 2. Generate tokens
        String accessToken =
                jwtProvider.generateAccessToken(
                        user.getId(),
                        user.getUsername()
                );

        String refreshToken =
                jwtProvider.generateRefreshToken(
                        user.getId(),
                        user.getUsername()
                );

        // 3. Remove existing session for this device
        sessionRepository
                .findByUserId(user.getId())
                .stream()
                .filter(
                        s ->
                                s.getDevice()
                                        .getDeviceId()
                                        .equals(
                                                request.getDeviceId()
                                        )
                )
                .forEach(
                        sessionRepository::delete
                );

        // 4. Create session
        //
        // IMPORTANT:
        // Use refresh-token expiration for session lifetime.
        LocalDateTime expiryDate =
                LocalDateTime.now()
                        .plusNanos(
                                jwtProvider
                                        .getRefreshTokenExpirationTime()
                                        * 1_000_000
                        );

        Session session =
                Session.builder()
                        .user(user)
                        .device(device)
                        .token(accessToken)
                        .refreshToken(refreshToken)
                        .expiresAt(expiryDate)
                        .build();

        sessionRepository.save(session);

        return AuthResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(
                        jwtProvider
                                .getTokenExpirationTime()
                                / 1000
                )
                .build();
    }

    @Override
    @Transactional
    public AuthResponse refreshToken(
            String refreshToken
    ) {

        log.info(
                "Refreshing token"
        );

        if (
                refreshToken == null ||
                refreshToken.isBlank()
        ) {
            throw new BadRequestException(
                    "Refresh token is required"
            );
        }

        // Validate JWT itself.
        if (
                !jwtProvider.validateToken(
                        refreshToken
                )
        ) {
            throw new BadRequestException(
                    "Invalid or expired refresh token"
            );
        }

        // Find active session.
        Session session =
                sessionRepository
                        .findByRefreshToken(
                                refreshToken
                        )
                        .orElseThrow(
                                () ->
                                        new BadRequestException(
                                                "Active session not found for refresh token"
                                        )
                        );

        // Check database session expiration.
        if (
                session.getExpiresAt()
                        .isBefore(
                                LocalDateTime.now()
                        )
        ) {

            sessionRepository.delete(
                    session
            );

            throw new BadRequestException(
                    "Session has expired. Please login again."
            );
        }

        User user =
                session.getUser();

        // Generate new tokens.
        //
        // Refresh-token rotation:
        // the old refresh token becomes invalid after this.
        String newAccessToken =
                jwtProvider.generateAccessToken(
                        user.getId(),
                        user.getUsername()
                );

        String newRefreshToken =
                jwtProvider.generateRefreshToken(
                        user.getId(),
                        user.getUsername()
                );

        // Update current session.
        session.setToken(
                newAccessToken
        );

        session.setRefreshToken(
                newRefreshToken
        );

        // IMPORTANT:
        // New session expiration follows the new refresh token.
        session.setExpiresAt(
                LocalDateTime.now()
                        .plusNanos(
                                jwtProvider
                                        .getRefreshTokenExpirationTime()
                                        * 1_000_000
                        )
        );

        sessionRepository.save(
                session
        );

        return AuthResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .expiresIn(
                        jwtProvider
                                .getTokenExpirationTime()
                                / 1000
                )
                .build();
    }

    @Override
    public boolean validateToken(
            String token
    ) {

        return jwtProvider.validateToken(
                token
        )
                &&
                sessionRepository
                        .findByToken(token)
                        .isPresent();
    }

    @Override
    @Transactional
    public void logout(
            String token
    ) {

        log.info(
                "Logging out user session"
        );

        Optional<Session> sessionOpt =
                sessionRepository
                        .findByToken(token);

        if (sessionOpt.isPresent()) {

            Session session =
                    sessionOpt.get();

            Device device =
                    session.getDevice();

            if (device != null) {

                device.setIsActive(
                        false
                );

                deviceRepository.save(
                        device
                );
            }

            sessionRepository.delete(
                    session
            );
        }
    }
}