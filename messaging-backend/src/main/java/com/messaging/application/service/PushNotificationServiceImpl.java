package com.messaging.application.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.AndroidConfig;
import com.messaging.domain.entity.Device;
import com.messaging.infrastructure.repository.DeviceRepository;
import com.messaging.infrastructure.repository.UserSettingsRepository;
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
    private final UserSettingsRepository userSettingsRepository;

    @Override
    public void sendPushNotification(Long recipientId, String title, String body, Map<String, String> data) {
        // The recipient's own notificationsEnabled setting decides whether a push is sent at all.
        // Checked here rather than at the two MessageServiceImpl call sites so private-chat and
        // group pushes cannot drift apart, and so nothing is spent delivering an alert the device
        // is only going to discard. The client checks its cached copy too, which covers the window
        // where a setting was changed while the phone was offline.
        boolean notificationsEnabled = userSettingsRepository.findByUserId(recipientId)
                .map(settings -> !Boolean.FALSE.equals(settings.getNotificationsEnabled()))
                .orElse(true);
        if (!notificationsEnabled) {
            log.debug("User {} has notifications disabled. Skipping push notification.", recipientId);
            return;
        }

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
                        // Data-only notifications ensure Android invokes
                        // MessagingFirebaseService in both foreground and background, where the
                        // app creates its own channel notification and deep link consistently.
                        .setAndroidConfig(AndroidConfig.builder()
                                .setPriority(AndroidConfig.Priority.HIGH)
                                .build())
                        .putAllData(withNotificationText(data, title, body))
                        .build();

                String response = FirebaseMessaging.getInstance().send(fcmMessage);
                log.info("Successfully sent FCM message to Device {}, response: {}", device.getDeviceId(), response);
            } catch (Exception e) {
                log.error("Failed to send FCM message to Device {}: {}", device.getDeviceId(), e.getMessage());
            }
        }
    }

    private Map<String, String> withNotificationText(Map<String, String> data, String title, String body) {
        java.util.HashMap<String, String> payload = new java.util.HashMap<>(data);
        payload.put("title", title);
        payload.put("body", body == null ? "" : body);
        return payload;
    }
}
