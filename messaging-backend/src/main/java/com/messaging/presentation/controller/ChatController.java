package com.messaging.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.messaging.application.service.MessageService;
import com.messaging.domain.dto.request.MessageRequest;
import com.messaging.domain.dto.response.ChatResponse;
import com.messaging.domain.dto.response.MessageResponse;
import com.messaging.domain.entity.Chat;
import com.messaging.infrastructure.repository.ChatRepository;
import com.messaging.infrastructure.repository.GroupMemberRepository;
import com.messaging.infrastructure.repository.UserSettingsRepository;
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
    private final GroupMemberRepository groupMemberRepository;
    private final UserSettingsRepository userSettingsRepository;
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

    @GetMapping("/archived")
    public ResponseEntity<List<ChatResponse>> getArchivedChats(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        List<ChatResponse> chats = messageService.getArchivedChats(principal.getId(), page, size);
        return ResponseEntity.ok(chats);
    }

    @PutMapping("/{chatId}/block")
    public ResponseEntity<Void> blockChat(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("chatId") Long chatId
    ) {
        Chat chat = chatRepository.findById(chatId).orElse(null);
        if (chat == null) {
            return ResponseEntity.notFound().build();
        }
        Long otherUserId = chat.getUser1().getId().equals(principal.getId())
                ? chat.getUser2().getId() : chat.getUser1().getId();
        messageService.blockUser(principal.getId(), otherUserId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{chatId}/block")
    public ResponseEntity<Void> unblockChat(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("chatId") Long chatId
    ) {
        Chat chat = chatRepository.findById(chatId).orElse(null);
        if (chat == null) {
            return ResponseEntity.notFound().build();
        }
        Long otherUserId = chat.getUser1().getId().equals(principal.getId())
                ? chat.getUser2().getId() : chat.getUser1().getId();
        messageService.unblockUser(principal.getId(), otherUserId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{chatId}/blocked")
    public ResponseEntity<Boolean> isChatBlocked(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("chatId") Long chatId
    ) {
        Chat chat = chatRepository.findById(chatId).orElse(null);
        if (chat == null) {
            return ResponseEntity.notFound().build();
        }
        Long otherUserId = chat.getUser1().getId().equals(principal.getId())
                ? chat.getUser2().getId() : chat.getUser1().getId();
        return ResponseEntity.ok(messageService.isBlocked(principal.getId(), otherUserId));
    }

    @PostMapping("/message")
    public ResponseEntity<MessageResponse> sendMessage(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @RequestBody MessageRequest request
    ) {
        MessageResponse message = messageService.sendMessage(principal.getId(), request);
        // REST is the reliable persistence path used by Android. Broadcast the stored response
        // exactly as the WebSocket send path does, so the recipient receives it in real time.
        boolean delivered = notifyMessageUpdate(principal.getId(), message, "CHAT_MESSAGE");

        // sendMessage() built this response *before* the broadcast above, when the only delivery
        // row that existed was PENDING - so its deliveryStatus is always "SENT", even when the
        // recipient was online and took the message microseconds later. The sender was therefore
        // told "sent" by the one response it trusts most, and the DELIVERY_STATUS frame that says
        // otherwise is written to the socket *before* this response is even flushed, so the client
        // usually applies the frame first and then overwrites it with this staler answer: a single
        // tick that never advanced, on a message the recipient already had.
        if (delivered) {
            message.setDeliveryStatus("DELIVERED");
        }
        return ResponseEntity.ok(message);
    }

    @GetMapping("/{chatId}/messages")
    public ResponseEntity<List<MessageResponse>> getChatMessages(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("chatId") Long chatId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        List<MessageResponse> messages = messageService.getChatMessages(principal.getId(), chatId, page, size);
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/group/{groupId}/messages")
    public ResponseEntity<List<MessageResponse>> getGroupMessages(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("groupId") Long groupId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        List<MessageResponse> messages = messageService.getGroupMessages(principal.getId(), groupId, page, size);
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
            if (!chat.getUser1().getId().equals(userId) && !chat.getUser2().getId().equals(userId)) {
                return;
            }
            // A receipt is still stored for unread-count purposes, but the reader can choose
            // not to disclose it to the sender.
            boolean sharesReadReceipts = userSettingsRepository.findByUserId(userId)
                    .map(settings -> !Boolean.FALSE.equals(settings.getShowReadReceipts()))
                    .orElse(true);
            if (!sharesReadReceipts) {
                return;
            }
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
        Long userId = principal.getId();
        MessageResponse message = messageService.editMessage(userId, messageId, text);
        notifyMessageUpdate(userId, message, "MESSAGE_UPDATE");
        return ResponseEntity.ok(message);
    }

    @DeleteMapping("/message/{messageId}")
    public ResponseEntity<MessageResponse> deleteMessage(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("messageId") Long messageId
    ) {
        Long userId = principal.getId();
        MessageResponse message = messageService.deleteMessage(userId, messageId);
        notifyMessageUpdate(userId, message, "MESSAGE_UPDATE");
        return ResponseEntity.ok(message);
    }

    @DeleteMapping("/{chatId}/messages")
    public ResponseEntity<Void> clearChat(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("chatId") Long chatId
    ) {
        messageService.clearChat(principal.getId(), chatId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{chatId}")
    public ResponseEntity<Void> deleteChat(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("chatId") Long chatId
    ) {
        messageService.deleteChat(principal.getId(), chatId);
        return ResponseEntity.ok().build();
    }

    /**
     * Broadcasts a message change to everyone else in the conversation.
     *
     * @return true when this was a new message that every other participant's socket accepted, i.e.
     *         when the message can honestly be reported as DELIVERED to its sender.
     */
    private boolean notifyMessageUpdate(Long senderId, MessageResponse response, String updateType) {
        boolean isNewMessage = "CHAT_MESSAGE".equals(updateType);
        try {
            WebSocketMessage outbound = WebSocketMessage.builder()
                    .type(updateType)
                    .senderId(senderId)
                    .chatId(response.getChatId())
                    .groupId(response.getGroupId())
                    .payload(response)
                    .build();

            String json = objectMapper.writeValueAsString(outbound);

            if (response.getChatId() != null) {
                Chat chat = chatRepository.findById(response.getChatId()).orElse(null);
                if (chat != null) {
                    Long recipientId = chat.getUser1().getId().equals(senderId)
                            ? chat.getUser2().getId() : chat.getUser1().getId();
                    boolean delivered = ChatWebSocketHandler.sendToUserStatic(recipientId, json);
                    if (delivered && isNewMessage) {
                        messageService.markMessageAsDelivered(recipientId, response.getId());
                        sendDeliveryStatus(senderId, recipientId, response);
                        return true;
                    }
                }
            } else if (response.getGroupId() != null) {
                // Previously this branch didn't exist at all - edits, deletes, and reactions on
                // group messages were silently never broadcast to other group members in real
                // time (they'd only see the change after their next full sync).
                List<Long> memberIds = groupMemberRepository.findMemberIdsByGroupId(response.getGroupId())
                        .stream()
                        .filter(id -> !id.equals(senderId))
                        .toList();
                boolean allDelivered = !memberIds.isEmpty();
                for (Long memberId : memberIds) {
                    boolean delivered = ChatWebSocketHandler.sendToUserStatic(memberId, json);
                    // This branch used to forward the frame and stop there - it never recorded the
                    // delivery - so a group message's DeliveryStatus rows stayed PENDING even
                    // though every member had it on screen, and group ticks never left one tick.
                    if (delivered && isNewMessage) {
                        messageService.markMessageAsDelivered(memberId, response.getId());
                    } else {
                        allDelivered = false;
                    }
                }
                // mapToMessageResponse aggregates group status as all-or-nothing, so the sender is
                // only told DELIVERED once every other member has actually received it. Members
                // who were offline get marked when their socket connects, via
                // ChatWebSocketHandler.processPendingDeliveries.
                if (allDelivered && isNewMessage) {
                    sendDeliveryStatus(senderId, senderId, response);
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("Failed to broadcast message update for messageId {}: {}", response.getId(), e.getMessage());
        }
        return false;
    }

    /** Tells [senderId] that their message has reached its recipients. */
    private void sendDeliveryStatus(Long senderId, Long reporterId, MessageResponse response) throws Exception {
        WebSocketMessage deliveryUpdate = WebSocketMessage.builder()
                .type("DELIVERY_STATUS")
                .senderId(reporterId)
                .recipientId(senderId)
                .chatId(response.getChatId())
                .groupId(response.getGroupId())
                .payload(java.util.Map.of("messageId", response.getId(), "status", "DELIVERED"))
                .build();
        ChatWebSocketHandler.sendToUserStatic(senderId, objectMapper.writeValueAsString(deliveryUpdate));
    }

    @PostMapping("/message/{messageId}/react")
    public ResponseEntity<Void> addReaction(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("messageId") Long messageId,
            @RequestParam("reaction") String reaction
    ) {
        MessageResponse message = messageService.addOrUpdateReaction(principal.getId(), messageId, reaction);
        notifyMessageUpdate(principal.getId(), message, "REACTION_UPDATE");
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/message/{messageId}/react")
    public ResponseEntity<Void> removeReaction(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @PathVariable("messageId") Long messageId
    ) {
        MessageResponse message = messageService.removeReaction(principal.getId(), messageId);
        notifyMessageUpdate(principal.getId(), message, "REACTION_UPDATE");
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
