# Milestones 3 & 4: Real-Time One-to-One Messaging, Groups & Attachments Complete

This document outlines the detailed architecture and changes completed for Milestones 3 and 4 of the messaging application.

---

## 1. Milestone 3: Real-Time One-to-One Messaging & WebSocket Infrastructure

This milestone established real-time bidirectional communication using WebSockets (STOMP/SockJS) on the backend and a native OkHttp WebSocket client on the Android application, complete with a persistent local SQLite Room cache.

### 1.1 Key Features
* **WebSocket Client & Server**: Enabled real-time connection lifecycle (connect, disconnect, connection status monitoring).
* **Delivery States & Receipts**: Dual-checkmark system for message delivery states:
  * `PENDING` (single grey check)
  * `DELIVERED` (double grey checks)
  * `READ` (double blue checks)
* **Typing Indicator**: Intercepts typing state notifications from a user and updates the recipient's UI with a live "typing..." subtitle in the app header.
* **Local Persistence Cache (Room)**: Messages are cached locally in SQLite via `MessageDao` and loaded instantly, while syncing with backend history in the background.

---

## 2. Milestone 4: Group Messaging & Rich Media Attachments

This milestone expanded the database schema and networks logic to support creation, member selection, and history syncing of group chats alongside standard private messages, plus multipart media file uploads.

### 2.1 Backend Features
* **Group Messaging Endpoint**: Added `GET /v1/chats/group/{groupId}/messages` to fetch group message logs.
* **Multipart File Uploads**: Added `POST /v1/attachments/upload` to handle multi-format attachments (Images, Videos, Documents, Voice Notes).

### 2.2 Android Integration
* **Unified Chats Database (Negative ID Strategy)**:
  * Unified both private chats and group chats inside `ChatEntity.kt` using a negative ID strategy (e.g. `-groupId` for group primary keys) to prevent key collision without database schema bloating.
  * Added mappings inside `ChatMapper.kt` to convert standard user groups (`GroupResponse`) into standard chat entities.
* **Group Management Screens**:
  * `CreateGroupScreen.kt`: Allows naming the group, adding descriptions, picking members from the contact list, and requesting creation.
  * `ChatListScreen.kt`: Dynamically renders group initials and secondary theme styling.
* **Media Attachment Picker & Bubble Renderer**:
  * Integrated Jetpack Compose's native file/image picker.
  * Created `AttachmentCard` to display inside `MessageBubble` when `attachmentPath` is present. Displays file names, sizes, and launches standard system download/viewer intents.

---

## 3. Current Compilation Status
Both the **Spring Boot backend** and the **Android application** build and compile successfully with zero errors.
* Android compile: `./gradlew compileDebugKotlin` passes.
* Backend compile: `mvn clean compile` passes.
