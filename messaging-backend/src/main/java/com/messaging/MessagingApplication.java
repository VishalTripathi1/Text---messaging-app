package com.messaging;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main entry point for Messaging Application Backend
 * 
 * Features:
 * - REST API for messaging functionality
 * - WebSocket support for real-time communication
 * - JWT authentication
 * - PostgreSQL persistence
 * - Firebase Cloud Messaging integration
 */
@SpringBootApplication
@EnableScheduling
public class MessagingApplication {

    public static void main(String[] args) {
        SpringApplication.run(MessagingApplication.class, args);
    }
}
