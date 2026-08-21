package com.messaging.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@Configuration
@Slf4j
public class FirebaseConfig {

    @PostConstruct
    public void initialize() {
        try {
            if (!FirebaseApp.getApps().isEmpty()) {
                log.info("Firebase Application is already initialized.");
                return;
            }

            String firebaseJson = System.getenv("FIREBASE_SERVICE_ACCOUNT_JSON");

            if (firebaseJson == null || firebaseJson.isBlank()) {
                log.warn("FIREBASE_SERVICE_ACCOUNT_JSON environment variable not found. Push notifications will be disabled.");
                return;
            }

            try (ByteArrayInputStream serviceAccount =
                         new ByteArrayInputStream(
                                 firebaseJson.getBytes(StandardCharsets.UTF_8))) {

                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .build();

                FirebaseApp.initializeApp(options);

                log.info("Firebase Application has been initialized successfully.");
            }

        } catch (Exception e) {
            log.error("Failed to initialize Firebase: {}", e.getMessage(), e);
        }
    }
}