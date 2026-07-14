package com.messaging.application.service;

import com.messaging.domain.dto.request.LoginRequest;
import com.messaging.domain.dto.request.RegisterRequest;
import com.messaging.domain.dto.response.AuthResponse;
import com.messaging.domain.entity.*;
import com.messaging.infrastructure.repository.*;
import com.messaging.infrastructure.security.JwtProvider;
import com.messaging.presentation.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private UserSettingsRepository userSettingsRepository;
    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtProvider jwtProvider;

    @InjectMocks
    private AuthServiceImpl authService;

    private RegisterRequest registerRequest;
    private User testUser;

    @BeforeEach
    void setUp() {
        registerRequest = RegisterRequest.builder()
                .username("testuser")
                .email("test@example.com")
                .password("password123")
                .displayName("Test User")
                .phoneNumber("1234567890")
                .build();

        testUser = User.builder()
                .id(1L)
                .username("testuser")
                .email("test@example.com")
                .passwordHash("hashedPassword")
                .phoneNumber("1234567890")
                .isActive(true)
                .build();
    }

    @Test
    void register_ShouldCreateUserAndReturnTokens() {
        when(userRepository.existsByUsername(registerRequest.getUsername())).thenReturn(false);
        when(userRepository.existsByEmail(registerRequest.getEmail())).thenReturn(false);
        when(passwordEncoder.encode(registerRequest.getPassword())).thenReturn("hashedPassword");
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        when(jwtProvider.generateAccessToken(anyLong(), anyString())).thenReturn("access_token");
        when(jwtProvider.generateRefreshToken(anyLong(), anyString())).thenReturn("refresh_token");
        when(jwtProvider.getTokenExpirationTime()).thenReturn(3600000L);

        AuthResponse response = authService.register(registerRequest);

        assertNotNull(response);
        assertEquals("testuser", response.getUsername());
        assertEquals("access_token", response.getAccessToken());
        assertEquals("refresh_token", response.getRefreshToken());

        verify(userRepository).save(any(User.class));
        verify(userProfileRepository).save(any(UserProfile.class));
        verify(userSettingsRepository).save(any(UserSettings.class));
        verify(deviceRepository).save(any(Device.class));
        verify(sessionRepository).save(any(Session.class));
    }

    @Test
    void register_DuplicateUsername_ShouldThrowException() {
        when(userRepository.existsByUsername(registerRequest.getUsername())).thenReturn(true);

        assertThrows(BadRequestException.class, () -> authService.register(registerRequest));

        verify(userRepository, never()).save(any(User.class));
    }
}
