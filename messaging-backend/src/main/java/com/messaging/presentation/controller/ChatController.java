package com.messaging.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messaging.application.service.MessageService;
import com.messaging.domain.dto.request.MessageRequest;
import com.messaging.domain.dto.response.ChatResponse;
import com.messaging.domain.dto.response.MessageResponse;
import com.messaging.domain.entity.Chat;
import com.messaging.infrastructure.repository.ChatRepository;
import com.messaging.infrastructure.security.CustomUserPrincipal;
import com.messaging.presentation.websocket.ChatWebSocketHandler;
import com.messaging.presentation.websocket.WebSocketMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ChatController - REST endpoints for chat sessions and message queries
 */
@Slf4j
@RestController
@RequestMapping("/v1/chats")
@RequiredArgsConstructor
public class ChatController {

    private final MessageService messageService;
    private final ChatRepository chatRepository;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<ChatResponse> getOrCreateChat(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam("recipientId") Long recipientId
    ) {
        ChatResponse chat = messageService.getOrCreateChat(principal.getId(), recipientId);
        return ResponseEntity.ok(chat);
    }

    @GetMapping
    public ResponseEntity<List<ChatResponse>> getUserChats(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        List<ChatResponse> chats = messageService.getUserChats(principal.getId(), page, size);
        return ResponseEntity.ok(chats);
    }

    @PostMapping("/message")
    public ResponseEntity<MessageResponse> sendMessage(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestBody MessageRequest request
    ) {
        MessageResponse message = messageService.sendMessage(principal.getId(), request);
        return ResponseEntity.ok(message);
    }

    @GetMapping("/{chatId}/messages")
    public ResponseEntity<List<MessageResponse>> getChatMessages(
            @PathVariable("chatId") Long chatId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        List<MessageResponse> messages = messageService.getChatMessages(chatId, page, size);
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/group/{groupId}/messages")
    public ResponseEntity<List<MessageResponse>> getGroupMessages(
            @PathVariable("groupId") Long groupId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        List<MessageResponse> messages = messageService.getGroupMessages(groupId, page, size);
        return ResponseEntity.ok(messages);
    }

    @PostMapping("/{chatId}/read")
    public ResponseEntity<Void> markChatAsRead(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("chatId") Long chatId
    ) {
        Long userId = principal.getId();
        messageService.markChatAsRead(userId, chatId);

        chatRepository.findById(chatId).ifPresent(chat -> {
            Long recipientId = chat.getUser1().getId().equals(userId) ? chat.getUser2().getId() : chat.getUser1().getId();
            try {
                WebSocketMessage wsMsg = WebSocketMessage.builder()
                        .type("READ_RECEIPT")
                        .senderId(userId)
                        .recipientId(recipientId)
                        .chatId(chatId)
                        .build();
                ChatWebSocketHandler.sendToUserStatic(recipientId, objectMapper.writeValueAsString(wsMsg));
            } catch (Exception e) {
                log.error("Failed to send WebSocket READ_RECEIPT notification to user {}: {}", recipientId, e.getMessage());
            }
        });

        return ResponseEntity.ok().build();
    }

    @PutMapping("/message/{messageId}")
    public ResponseEntity<MessageResponse> editMessage(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("messageId") Long messageId,
            @RequestParam("text") String text
    ) {
        MessageResponse message = messageService.editMessage(principal.getId(), messageId, text);
        return ResponseEntity.ok(message);
    }

    @DeleteMapping("/message/{messageId}")
    public ResponseEntity<MessageResponse> deleteMessage(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("messageId") Long messageId
    ) {
        MessageResponse message = messageService.deleteMessage(principal.getId(), messageId);
        return ResponseEntity.ok(message);
    }

    @PostMapping("/message/{messageId}/react")
    public ResponseEntity<Void> addReaction(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("messageId") Long messageId,
            @RequestParam("reaction") String reaction
    ) {
        messageService.addOrUpdateReaction(principal.getId(), messageId, reaction);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/message/{messageId}/react")
    public ResponseEntity<Void> removeReaction(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("messageId") Long messageId
    ) {
        messageService.removeReaction(principal.getId(), messageId);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{chatId}/pin")
    public ResponseEntity<ChatResponse> pinChat(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("chatId") Long chatId,
            @RequestParam("pinned") boolean pinned
    ) {
        ChatResponse chat = messageService.togglePinChat(principal.getId(), chatId, pinned);
        return ResponseEntity.ok(chat);
    }

    @PutMapping("/{chatId}/archive")
    public ResponseEntity<ChatResponse> archiveChat(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("chatId") Long chatId,
            @RequestParam("archived") boolean archived
    ) {
        ChatResponse chat = messageService.toggleArchiveChat(principal.getId(), chatId, archived);
        return ResponseEntity.ok(chat);
    }

    @GetMapping("/search")
    public ResponseEntity<List<MessageResponse>> searchMessages(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam("query") String query,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        List<MessageResponse> messages = messageService.searchMessages(principal.getId(), query, page, size);
        return ResponseEntity.ok(messages);
    }
}
