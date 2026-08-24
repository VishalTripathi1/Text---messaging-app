package com.messaging.presentation.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messaging.application.service.MessageService;
import com.messaging.domain.dto.request.MessageRequest;
import com.messaging.domain.dto.response.MessageResponse;
import com.messaging.domain.entity.Chat;
import com.messaging.domain.entity.DeliveryStatus;
import com.messaging.domain.entity.UserProfile;
import com.messaging.infrastructure.repository.ChatRepository;
import com.messaging.infrastructure.repository.DeliveryStatusRepository;
import com.messaging.infrastructure.repository.GroupRepository;
import com.messaging.infrastructure.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatWebSocketHandler - Intercepts text frames and handles real-time distribution
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final MessageService messageService;
    private final UserProfileRepository userProfileRepository;
    private final GroupRepository groupRepository;
    private final DeliveryStatusRepository deliveryStatusRepository;
    private final com.messaging.infrastructure.repository.UserSettingsRepository userSettingsRepository;
    private final ChatRepository chatRepository;

    // Thread-safe map of active WebSocket connections
    private static final Map<Long, WebSocketSession> userSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            userSessions.put(userId, session);
            log.info("User {} connected to WebSockets. Total sessions: {}", userId, userSessions.size());
            
            updateUserStatus(userId, UserProfile.UserStatus.ONLINE);
            broadcastStatus(userId, "ONLINE");
            processPendingDeliveries(userId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            boolean wasCurrentSession = userSessions.remove(userId, session);
            if (!wasCurrentSession) {
                return;
            }
            log.info("User {} disconnected from WebSockets. Total sessions: {}", userId, userSessions.size());
            
            updateUserStatus(userId, UserProfile.UserStatus.OFFLINE);
            broadcastStatus(userId, "OFFLINE");
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long senderId = (Long) session.getAttributes().get("userId");
        if (senderId == null) return;

        String payloadStr = message.getPayload();
        WebSocketMessage wsMessage = objectMapper.readValue(payloadStr, WebSocketMessage.class);
        wsMessage.setSenderId(senderId);

        switch (wsMessage.getType()) {
            case "CHAT_MESSAGE":
                handleChatMessage(wsMessage);
                break;
            case "TYPING":
                handleTyping(wsMessage);
                break;
            case "READ_RECEIPT":
                handleReadReceipt(wsMessage);
                break;
            case "DELIVERY_STATUS":
                handleDeliveryStatus(wsMessage);
                break;
            default:
                log.warn("Unknown WebSocket message type: {}", wsMessage.getType());
        }
    }

    private void handleChatMessage(WebSocketMessage wsMessage) throws IOException {
        MessageRequest request = objectMapper.convertValue(wsMessage.getPayload(), MessageRequest.class);
        MessageResponse response = messageService.sendMessage(wsMessage.getSenderId(), request);
        
        WebSocketMessage outbound = WebSocketMessage.builder()
                .type("CHAT_MESSAGE")
                .senderId(wsMessage.getSenderId())
                .recipientId(wsMessage.getRecipientId())
                .chatId(response.getChatId())
                .groupId(response.getGroupId())
                .payload(response)
                .build();

        if (response.getGroupId() != null) {
            String json = objectMapper.writeValueAsString(outbound);
            List<Long> memberIds = groupRepository.findMemberUserIds(response.getGroupId());
            for (Long memberId : memberIds) {
                if (!memberId.equals(wsMessage.getSenderId())) {
                    boolean delivered = sendToUser(memberId, json);
                    // Mirrors the private-chat branch below: mark delivered immediately for
                    // members who are online right now, instead of only ever catching up via
                    // processPendingDeliveries() the next time they reconnect.
                    if (delivered) {
                        messageService.markMessageAsDelivered(memberId, response.getId());
                    }
                }
            }
            sendToUser(wsMessage.getSenderId(), json);
        } else {
            Long recipientId = wsMessage.getRecipientId();
            if (recipientId == null && response.getChatId() != null) {
                Chat chat = chatRepository.findById(response.getChatId()).orElse(null);
                if (chat != null) {
                    recipientId = chat.getUser1().getId().equals(wsMessage.getSenderId()) ? chat.getUser2().getId() : chat.getUser1().getId();
                }
            }

            if (recipientId != null) {
                outbound.setRecipientId(recipientId);
                String jsonForRecipient = objectMapper.writeValueAsString(outbound);
                boolean delivered = sendToUser(recipientId, jsonForRecipient);
                if (delivered) {
                    messageService.markMessageAsDelivered(recipientId, response.getId());
                    
                    WebSocketMessage deliveryMsg = WebSocketMessage.builder()
                            .type("DELIVERY_STATUS")
                            .senderId(recipientId)
                            .recipientId(wsMessage.getSenderId())
                            .chatId(response.getChatId())
                            .payload(Map.of("messageId", response.getId(), "status", "DELIVERED"))
                            .build();
                    sendToUser(wsMessage.getSenderId(), objectMapper.writeValueAsString(deliveryMsg));
                }
            }
            
            // Acknowledge to sender
            String jsonForSender = objectMapper.writeValueAsString(outbound);
            sendToUser(wsMessage.getSenderId(), jsonForSender);
        }
    }

    private void handleTyping(WebSocketMessage wsMessage) throws IOException {
        // showTypingIndicator existed as a stored setting but was never actually checked - the
        // server broadcast typing events unconditionally regardless of whether the sender had
        // opted out.
        boolean typingEnabled = userSettingsRepository.findByUserId(wsMessage.getSenderId())
                .map(s -> !Boolean.FALSE.equals(s.getShowTypingIndicator()))
                .orElse(true);
        if (!typingEnabled) {
            return;
        }

        String json = objectMapper.writeValueAsString(wsMessage);
        if (wsMessage.getGroupId() != null) {
            List<Long> memberIds = groupRepository.findMemberUserIds(wsMessage.getGroupId());
            for (Long memberId : memberIds) {
                if (!memberId.equals(wsMessage.getSenderId())) {
                    sendToUser(memberId, json);
                }
            }
        } else if (wsMessage.getRecipientId() != null) {
            sendToUser(wsMessage.getRecipientId(), json);
        }
    }

    private void handleReadReceipt(WebSocketMessage wsMessage) throws IOException {
        Long senderId = wsMessage.getSenderId();
        if (wsMessage.getChatId() != null) {
            messageService.markChatAsRead(senderId, wsMessage.getChatId());
            
            Long recipientId = wsMessage.getRecipientId();
            if (recipientId == null) {
                Chat chat = chatRepository.findById(wsMessage.getChatId()).orElse(null);
                if (chat != null) {
                    recipientId = chat.getUser1().getId().equals(senderId) ? chat.getUser2().getId() : chat.getUser1().getId();
                }
            }

            if (recipientId != null) {
                boolean sharesReadReceipts = userSettingsRepository.findByUserId(senderId)
                        .map(settings -> !Boolean.FALSE.equals(settings.getShowReadReceipts()))
                        .orElse(true);
                if (!sharesReadReceipts) {
                    return;
                }
                wsMessage.setRecipientId(recipientId);
                String json = objectMapper.writeValueAsString(wsMessage);
                sendToUser(recipientId, json);
            }
        }
    }

    private void handleDeliveryStatus(WebSocketMessage wsMessage) throws IOException {
        Map<?, ?> payloadMap = (Map<?, ?>) wsMessage.getPayload();
        Number messageIdNum = (Number) payloadMap.get("messageId");
        if (messageIdNum != null) {
            Long messageId = messageIdNum.longValue();
            messageService.markMessageAsDelivered(wsMessage.getSenderId(), messageId);
        }

        Long recipientId = wsMessage.getRecipientId();
        if (recipientId == null && wsMessage.getChatId() != null) {
            Chat chat = chatRepository.findById(wsMessage.getChatId()).orElse(null);
            if (chat != null) {
                recipientId = chat.getUser1().getId().equals(wsMessage.getSenderId()) ? chat.getUser2().getId() : chat.getUser1().getId();
            }
        }

        if (recipientId != null) {
            wsMessage.setRecipientId(recipientId);
            String json = objectMapper.writeValueAsString(wsMessage);
            sendToUser(recipientId, json);
        }
    }

    public static boolean sendToUserStatic(Long userId, String json) {
        WebSocketSession session = userSessions.get(userId);
        if (session != null && session.isOpen()) {
            // Spring's WebSocketSession.sendMessage() is not thread-safe: if two threads write
            // to the same session concurrently (e.g. two different senders messaging this user
            // at nearly the same instant), the underlying frame writer can throw
            // "IllegalStateException: TEXT_PARTIAL_WRITING" and drop the message. Synchronizing
            // per-session (not globally) serializes writes to a given user's socket without
            // blocking unrelated sessions.
            synchronized (session) {
                try {
                    session.sendMessage(new TextMessage(json));
                    return true;
                } catch (IOException e) {
                    log.error("Failed to send WebSocket message to user {}: {}", userId, e.getMessage());
                }
            }
        }
        return false;
    }

    private boolean sendToUser(Long userId, String json) {
        return sendToUserStatic(userId, json);
    }

    private void broadcastStatus(Long userId, String status) {
        WebSocketMessage wsMessage = WebSocketMessage.builder()
                .type("USER_STATUS")
                .senderId(userId)
                .payload(Map.of("userId", userId, "status", status))
                .build();
        try {
            String json = objectMapper.writeValueAsString(wsMessage);
            for (WebSocketSession session : userSessions.values()) {
                Long sessionUserId = (Long) session.getAttributes().get("userId");
                if (sessionUserId != null && !sessionUserId.equals(userId)) {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(json));
                    }
                }
            }
        } catch (IOException e) {
            log.error("Failed to broadcast status for user {}: {}", userId, e.getMessage());
        }
    }

    private void updateUserStatus(Long userId, UserProfile.UserStatus status) {
        userProfileRepository.findByUserId(userId).ifPresent(profile -> {
            profile.setStatus(status);
            if (status == UserProfile.UserStatus.OFFLINE) {
                profile.setLastSeen(LocalDateTime.now());
            }
            userProfileRepository.save(profile);
        });
    }

    private void processPendingDeliveries(Long recipientId) {
        List<DeliveryStatus> pendingList = deliveryStatusRepository.findByRecipientIdAndStatusWithMessageAndSender(
                recipientId, DeliveryStatus.DeliveryStatusType.PENDING);

        for (DeliveryStatus status : pendingList) {
            status.setStatus(DeliveryStatus.DeliveryStatusType.DELIVERED);
            status.setDeliveredAt(LocalDateTime.now());
        }
        // One batch write instead of N individual save() calls.
        deliveryStatusRepository.saveAll(pendingList);

        for (DeliveryStatus status : pendingList) {
            Long senderId = status.getMessage().getSender().getId();
            Long chatId = status.getMessage().getChat() != null ? status.getMessage().getChat().getId() : null;

            try {
                WebSocketMessage deliveryMsg = WebSocketMessage.builder()
                        .type("DELIVERY_STATUS")
                        .senderId(recipientId)
                        .recipientId(senderId)
                        .chatId(chatId)
                        .payload(Map.of("messageId", status.getMessage().getId(), "status", "DELIVERED"))
                        .build();
                sendToUser(senderId, objectMapper.writeValueAsString(deliveryMsg));
            } catch (Exception e) {
                log.error("Failed to notify sender {} of pending delivery: {}", senderId, e.getMessage());
            }
        }
    }
}
