package com.messaging.application.service;

import com.messaging.domain.dto.request.DeviceRegisterRequest;
import com.messaging.domain.entity.Device;
import com.messaging.domain.entity.User;
import com.messaging.infrastructure.repository.DeviceRepository;
import com.messaging.infrastructure.repository.UserRepository;
import com.messaging.presentation.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * DeviceServiceImpl - Implementation of DeviceService
 */
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void registerDevice(Long userId, DeviceRegisterRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Device device = deviceRepository.findByDeviceId(request.getDeviceId())
                .orElseGet(() -> Device.builder()
                        .deviceId(request.getDeviceId())
                        .build());

        device.setUser(user);
        device.setDeviceName(request.getDeviceName());
        device.setDeviceType(request.getDeviceType());
        device.setOsVersion(request.getOsVersion());
        device.setAppVersion(request.getAppVersion());
        device.setFcmToken(request.getFcmToken());
        device.setIsActive(true);
        device.setLastActive(LocalDateTime.now());

        deviceRepository.save(device);
    }

    @Override
    @Transactional
    public void unregisterDevice(Long userId, String deviceId) {
        deviceRepository.findByDeviceId(deviceId).ifPresent(device -> {
            if (device.getUser().getId().equals(userId)) {
                device.setIsActive(false);
                device.setFcmToken(null);
                deviceRepository.save(device);
            }
        });
    }
}
