package com.messaging.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.InputStream;

@Configuration
@Slf4j
public class FirebaseConfig {

    private static final String FIREBASE_CREDENTIALS_PATH =
            "/etc/secrets/firebase-service-account.json";

    @PostConstruct
    public void initialize() {
        try {
            if (!FirebaseApp.getApps().isEmpty()) {
                log.info("Firebase Application is already initialized.");
                return;
            }

            try (InputStream serviceAccount =
                         new FileInputStream(FIREBASE_CREDENTIALS_PATH)) {

                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(
                                GoogleCredentials.fromStream(serviceAccount)
                        )
                        .build();

                FirebaseApp.initializeApp(options);

                log.info("Firebase Application has been initialized successfully.");

            }

        } catch (Exception e) {
            log.error("Failed to initialize Firebase: {}", e.getMessage(), e);
        }
    }
}