# Messaging Application - Production-Quality WhatsApp Alternative

A comprehensive messaging application built from scratch for learning and portfolio purposes using only **FREE tools and services**.

## 🎯 Features

### MVP (Milestone 1-12)
- ✅ User Registration & Login with JWT
- ✅ One-to-One Chat
- ✅ Group Chat
- ✅ Real-time Messaging (WebSockets)
- ✅ Media Sharing (Images, Videos, Documents, Voice)
- ✅ Offline Support with Auto-Sync
- ✅ Read Receipts & Delivery Status
- ✅ Typing Indicators
- ✅ Message Reactions (Emoji)
- ✅ Full-Text Search
- ✅ Firebase Cloud Messaging
- ✅ Message Edit & Delete
- ✅ Pin & Archive Chats
- ✅ User Profiles
- ✅ Dark Mode Support
- ✅ Online/Offline Status

### Future Enhancements
- 🔜 Voice Calls
- 🔜 Video Calls
- 🔜 Stories/Status
- 🔜 Communities
- 🔜 AI Assistant
- 🔜 Screen Sharing
- 🔜 End-to-End Encryption (Signal Protocol)

## 🏗️ Architecture

- **Clean Architecture** with SOLID principles
- **Layered Architecture**: Presentation → Domain → Data
- **MVVM** (Android) + **Repository Pattern**
- **Dependency Injection**: Hilt (Android), Spring (Backend)
- **Reactive Programming**: Kotlin Coroutines, Java CompletableFuture
- **Modular & Scalable** design for enterprise applications

## 🛠️ Tech Stack

### Backend
- **Framework**: Spring Boot 3.1.0
- **Language**: Java 17
- **Database**: PostgreSQL 15
- **ORM**: Hibernate/JPA
- **API**: REST + WebSocket
- **Authentication**: JWT
- **Build**: Maven
- **Testing**: JUnit + Mockito

### Android
- **Language**: Kotlin
- **UI**: Jetpack Compose + Material Design 3
- **Architecture**: MVVM + Repository Pattern
- **Database**: Room
- **Networking**: Retrofit + OkHttp
- **DI**: Hilt
- **Async**: Coroutines + Flow
- **Notifications**: Firebase Cloud Messaging

### DevOps
- **Container**: Docker (PostgreSQL only)
- **Version Control**: Git + GitHub
- **Local Development**: All services run locally

## 📋 Project Structure

```
messaging-app/
├── messaging-backend/          # Spring Boot backend
│   ├── src/main/java/
│   │   └── com/messaging/
│   │       ├── config/                  # Configuration
│   │       ├── domain/                  # Entities & DTOs
│   │       ├── application/             # Business logic
│   │       ├── infrastructure/          # Repositories, WebSocket
│   │       └── presentation/            # Controllers & Exception Handling
│   └── pom.xml
│
├── messaging-android/          # Android app
│   ├── app/src/main/
│   │   ├── java/com/messaging/
│   │   │   ├── di/                      # Hilt modules
│   │   │   ├── presentation/            # UI Screens & ViewModels
│   │   │   ├── domain/                  # Use Cases & Entities
│   │   │   ├── data/                    # Repositories & Data Sources
│   │   │   └── infrastructure/          # Network, WebSocket
│   │   └── res/
│   └── build.gradle.kts
│
├── docker-compose.yml          # PostgreSQL container
├── docs/                        # Documentation
└── scripts/                     # Utility scripts
```

## 🚀 Getting Started

### Prerequisites
- Java 17+
- Android Studio Giraffe+
- Docker & Docker Compose
- Git
- Maven

### Backend Setup

1. **Start PostgreSQL**
```bash
cd "messaging app"
docker-compose up -d
```

2. **Navigate to backend**
```bash
cd messaging-backend
```

3. **Build and run**
```bash
mvn clean install
mvn spring-boot:run
```

Backend runs on: `http://localhost:8080`

### Android Setup

1. **Navigate to Android project**
```bash
cd messaging-android
```

2. **Open in Android Studio**
```bash
Android Studio > File > Open > messaging-android
```

3. **Sync Gradle** and run on emulator/device

### Firebase Cloud Messaging setup

Push notifications require Firebase configuration; the project deliberately does not contain
secrets. The current workspace has neither Android's `app/google-services.json` nor the backend
`FIREBASE_SERVICE_ACCOUNT_JSON` environment variable, so permission alone cannot produce a push.

1. Create/select a Firebase project and add Android app ID `com.messaging`.
2. Download its `google-services.json` into `messaging-android/app/`, add the Google Services
   Gradle plugin, then rebuild the app.
3. Run the backend with `FIREBASE_SERVICE_ACCOUNT_JSON` set to that project's service-account
   JSON (keep it out of git).
4. Test on a physical device or an AVD image labeled **Google Play** / **Google APIs**. A plain
   system-image emulator cannot obtain an FCM registration token.

### Access

- **Backend API**: `http://localhost:8080/api`
- **Swagger Docs**: `http://localhost:8080/swagger-ui.html`
- **Database**: `localhost:5432` (postgres/postgres123)

## 📚 API Documentation

See [API.md](docs/API.md) for complete REST API documentation.

### Authentication Endpoints
```
POST   /api/v1/auth/register      # Register new user
POST   /api/v1/auth/login         # Login with credentials
POST   /api/v1/auth/refresh-token # Refresh access token
POST   /api/v1/auth/logout        # Logout user
```

### Chat Endpoints
```
GET    /api/v1/chats              # Get all chats
POST   /api/v1/chats/{id}/messages   # Send message
GET    /api/v1/chats/{id}/messages   # Get chat history
```

### WebSocket
```
WebSocket: ws://localhost:8080/ws/chat?token=JWT_TOKEN
```

## 🎓 Learning Path

This project is structured as a **12-week tutorial** with clear milestones:

1. **Milestone 1**: Project Setup & Infrastructure ✅
2. **Milestone 2**: Authentication System
3. **Milestone 3**: One-to-One Chat (REST)
4. **Milestone 4**: WebSocket Integration
5. **Milestone 5**: Group Chat
6. **Milestone 6**: Media Sharing
7. **Milestone 7**: Push Notifications
8. **Milestone 8**: Message Search
9. **Milestone 9**: Offline Support & Sync
10. **Milestone 10**: Advanced Features
11. **Milestone 11**: Security & Testing
12. **Milestone 12**: Deployment & Documentation

Each milestone includes:
- Architecture explanation
- Production-quality code
- Comprehensive testing
- Clear learning objectives

## 🧪 Testing

### Backend
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=AuthServiceTest
```

### Android
```bash
# Unit tests
./gradlew test

# Instrumentation tests
./gradlew connectedAndroidTest
```

## 📖 Documentation

- [Complete Architecture Design](docs/ARCHITECTURE.md)
- [Database Schema](docs/DATABASE.md)
- [API Reference](docs/API.md)
- [WebSocket Design](docs/WEBSOCKET.md)
- [Setup & Deployment Guide](docs/SETUP.md)
- [Security & Best Practices](docs/SECURITY.md)

## 🔐 Security

- ✅ HTTPS/TLS only
- ✅ JWT authentication with refresh tokens
- ✅ BCrypt password hashing (12 rounds)
- ✅ SQL injection prevention (JPA)
- ✅ Input validation on all endpoints
- ✅ Rate limiting (future implementation)
- ✅ Secure storage (EncryptedSharedPreferences)
- ✅ CORS protection
- ⏳ E2EE with Signal Protocol (Phase 2)

## 📞 Support

For issues, questions, or contributions:
1. Open an issue on GitHub
2. Follow coding standards (Clean Code principles)
3. Write tests for new features
4. Submit pull request with clear description

## 📄 License

Open source for educational purposes

---
