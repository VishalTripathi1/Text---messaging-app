package com.messaging.domain.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DeviceRegisterRequest - DTO representing a request to register a device token
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceRegisterRequest {

    @NotBlank(message = "Device ID is required")
    private String deviceId;

    private String deviceName;

    private String deviceType;

    private String osVersion;

    private String appVersion;

    @NotBlank(message = "FCM token is required")
    private String fcmToken;
}
