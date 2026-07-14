package com.messaging.domain.dto.request;

import lombok.*;
import jakarta.validation.constraints.*;

/**
 * LoginRequest DTO - User login payload
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginRequest {

    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;

    @NotBlank(message = "Device ID is required")
    private String deviceId;

    private String deviceName;
}
