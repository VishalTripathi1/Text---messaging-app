package com.messaging.presentation.websocket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocketMessage - Universal data structure for real-time messages
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebSocketMessage {
    private String type; // CHAT_MESSAGE, TYPING, READ_RECEIPT, DELIVERY_STATUS, USER_STATUS
    private Long senderId;
    private Long recipientId;
    private Long chatId;
    private Long groupId;
    private Object payload; // Dynamic payload depending on the type
}
