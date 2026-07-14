package com.messaging.presentation.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messaging.application.service.MessageService;
import com.messaging.domain.dto.request.MessageRequest;
import com.messaging.domain.dto.response.MessageResponse;
import com.messaging.domain.entity.UserProfile;
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
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            userSessions.remove(userId);
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
        
        String json = objectMapper.writeValueAsString(outbound);

        if (response.getGroupId() != null) {
            List<Long> memberIds = groupRepository.findMemberUserIds(response.getGroupId());
            for (Long memberId : memberIds) {
                if (!memberId.equals(wsMessage.getSenderId())) {
                    sendToUser(memberId, json);
                }
            }
        } else {
            Long recipientId = wsMessage.getRecipientId();
            if (recipientId != null) {
                boolean delivered = sendToUser(recipientId, json);
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
            sendToUser(wsMessage.getSenderId(), json);
        }
    }

    private void handleTyping(WebSocketMessage wsMessage) throws IOException {
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
        if (wsMessage.getChatId() != null) {
            messageService.markChatAsRead(wsMessage.getSenderId(), wsMessage.getChatId());
        }

        String json = objectMapper.writeValueAsString(wsMessage);
        if (wsMessage.getRecipientId() != null) {
            sendToUser(wsMessage.getRecipientId(), json);
        }
    }

    private void handleDeliveryStatus(WebSocketMessage wsMessage) throws IOException {
        Map<?, ?> payloadMap = (Map<?, ?>) wsMessage.getPayload();
        Number messageIdNum = (Number) payloadMap.get("messageId");
        if (messageIdNum != null) {
            Long messageId = messageIdNum.longValue();
            messageService.markMessageAsDelivered(wsMessage.getSenderId(), messageId);
        }

        String json = objectMapper.writeValueAsString(wsMessage);
        if (wsMessage.getRecipientId() != null) {
            sendToUser(wsMessage.getRecipientId(), json);
        }
    }

    private boolean sendToUser(Long userId, String json) {
        WebSocketSession session = userSessions.get(userId);
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(json));
                return true;
            } catch (IOException e) {
                log.error("Failed to send WebSocket message to user {}: {}", userId, e.getMessage());
            }
        }
        return false;
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
                    session.sendMessage(new TextMessage(json));
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
}
