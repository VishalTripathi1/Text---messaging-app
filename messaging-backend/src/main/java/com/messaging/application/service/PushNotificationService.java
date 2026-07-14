package com.messaging.application.service;

import java.util.Map;

/**
 * PushNotificationService - Interface to dispatch push notifications to offline user devices
 */
public interface PushNotificationService {
    void sendPushNotification(Long recipientId, String title, String body, Map<String, String> data);
}
