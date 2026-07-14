package com.messaging.application.service;

import com.messaging.domain.dto.request.LoginRequest;
import com.messaging.domain.dto.request.RegisterRequest;
import com.messaging.domain.dto.response.AuthResponse;

/**
 * AuthService - Service interface for authentication operations
 */
public interface AuthService {

    /**
     * Register a new user
     */
    AuthResponse register(RegisterRequest request);

    /**
     * Authenticate a user and create a session
     */
    AuthResponse login(LoginRequest request);

    /**
     * Refresh access and refresh tokens
     */
    AuthResponse refreshToken(String refreshToken);

    /**
     * Validate an access token
     */
    boolean validateToken(String token);

    /**
     * Terminate the session by invalidating the token
     */
    void logout(String token);
}
