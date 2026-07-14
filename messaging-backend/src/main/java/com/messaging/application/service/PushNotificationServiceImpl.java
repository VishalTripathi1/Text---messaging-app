package com.messaging.application.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Notification;
import com.messaging.domain.entity.Device;
import com.messaging.infrastructure.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * PushNotificationServiceImpl - Implements Firebase Cloud Messaging (FCM) dispatch
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PushNotificationServiceImpl implements PushNotificationService {

    private final DeviceRepository deviceRepository;

    @Override
    public void sendPushNotification(Long recipientId, String title, String body, Map<String, String> data) {
        List<Device> activeDevices = deviceRepository.findByUserIdAndIsActiveTrue(recipientId);
        
        if (activeDevices.isEmpty()) {
            log.info("No active devices found for user ID: {}. Skipping push notification.", recipientId);
            return;
        }

        boolean isFirebaseInitialized = !FirebaseApp.getApps().isEmpty();

        for (Device device : activeDevices) {
            String token = device.getFcmToken();
            if (token == null || token.isBlank()) {
                continue;
            }

            if (!isFirebaseInitialized) {
                log.info("[MOCK PUSH] Sent to User {}, Device {} (Token: {}): Title='{}', Body='{}', Data={}", 
                        recipientId, device.getDeviceId(), token, title, body, data);
                continue;
            }

            try {
                com.google.firebase.messaging.Message fcmMessage = com.google.firebase.messaging.Message.builder()
                        .setToken(token)
                        .setNotification(Notification.builder()
                                .setTitle(title)
                                .setBody(body)
                                .build())
                        .putAllData(data)
                        .build();

                String response = FirebaseMessaging.getInstance().send(fcmMessage);
                log.info("Successfully sent FCM message to Device {}, response: {}", device.getDeviceId(), response);
            } catch (Exception e) {
                log.error("Failed to send FCM message to Device {}: {}", device.getDeviceId(), e.getMessage());
            }
        }
    }
}
