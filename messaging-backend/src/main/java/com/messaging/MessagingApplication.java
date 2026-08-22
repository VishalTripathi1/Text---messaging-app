package com.messaging;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@SpringBootApplication
@EnableScheduling
public class MessagingApplication {

    private static final Logger log = LoggerFactory.getLogger(MessagingApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(MessagingApplication.class, args);
        initializeFirebase();
    }

    private static void initializeFirebase() {
        try {
            if (!FirebaseApp.getApps().isEmpty()) {
                log.info("[FIREBASE-BOOT] Firebase already initialized.");
                return;
            }

            String firebaseJson = System.getenv("FIREBASE_SERVICE_ACCOUNT_JSON");
            log.info("[FIREBASE-BOOT] env check: present={}, length={}",
                    firebaseJson != null && !firebaseJson.isBlank(),
                    firebaseJson == null ? 0 : firebaseJson.length());

            if (firebaseJson == null || firebaseJson.isBlank()) {
                log.warn("[FIREBASE-BOOT] FIREBASE_SERVICE_ACCOUNT_JSON not set. Push disabled.");
                return;
            }

            try (ByteArrayInputStream serviceAccount =
                         new ByteArrayInputStream(firebaseJson.getBytes(StandardCharsets.UTF_8))) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .build();
                FirebaseApp.initializeApp(options);
                log.info("[FIREBASE-BOOT] Firebase initialized successfully.");
            }
        } catch (Exception e) {
            log.error("[FIREBASE-BOOT] Failed to initialize Firebase: {}", e.getMessage(), e);
        }
    }
}