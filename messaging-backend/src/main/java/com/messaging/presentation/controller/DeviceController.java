package com.messaging.presentation.controller;

import com.messaging.application.service.DeviceService;
import com.messaging.domain.dto.request.DeviceRegisterRequest;
import com.messaging.infrastructure.security.CustomUserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * DeviceController - REST endpoints for registering FCM push notification tokens
 */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @PostMapping
    public ResponseEntity<Void> registerDevice(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody DeviceRegisterRequest request
    ) {
        deviceService.registerDevice(principal.getId(), request);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> unregisterDevice(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("deviceId") String deviceId
    ) {
        deviceService.unregisterDevice(principal.getId(), deviceId);
        return ResponseEntity.ok().build();
    }
}
