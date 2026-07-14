package com.messaging.application.service;

import com.messaging.domain.dto.request.DeviceRegisterRequest;

/**
 * DeviceService - Interface for managing user devices and push notification tokens
 */
public interface DeviceService {
    void registerDevice(Long userId, DeviceRegisterRequest request);
    void unregisterDevice(Long userId, String deviceId);
}
