package com.messaging.application.service;

import com.messaging.domain.dto.request.MessageRequest;
import com.messaging.domain.dto.response.ChatResponse;
import com.messaging.domain.dto.response.MessageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * MessageService - Interface for message and chat handling
 */
public interface MessageService {
    
    ChatResponse getOrCreateChat(Long userId, Long otherUserId);
    
    List<ChatResponse> getUserChats(Long userId, int page, int size);

    List<ChatResponse> getArchivedChats(Long userId, int page, int size);
    
    MessageResponse sendMessage(Long senderId, MessageRequest request);
    
    List<MessageResponse> getChatMessages(Long userId, Long chatId, int page, int size);
    
    List<MessageResponse> getGroupMessages(Long userId, Long groupId, int page, int size);
    
    void markChatAsRead(Long userId, Long chatId);
    
    void markMessageAsDelivered(Long userId, Long messageId);

    MessageResponse editMessage(Long userId, Long messageId, String newText);

    MessageResponse deleteMessage(Long userId, Long messageId);

    boolean isBlocked(Long blockerId, Long targetUserId);

    boolean blockUser(Long blockerId, Long targetUserId);

    boolean unblockUser(Long blockerId, Long targetUserId);

    MessageResponse addOrUpdateReaction(Long userId, Long messageId, String reaction);

    MessageResponse removeReaction(Long userId, Long messageId);

    ChatResponse togglePinChat(Long userId, Long chatId, boolean pinned);

    ChatResponse toggleArchiveChat(Long userId, Long chatId, boolean archived);

    List<MessageResponse> searchMessages(Long userId, String query, int page, int size);
}
