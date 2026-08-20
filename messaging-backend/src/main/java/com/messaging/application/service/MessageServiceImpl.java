package com.messaging.application.service;

import com.messaging.domain.dto.request.MessageRequest;
import com.messaging.domain.dto.response.ChatResponse;
import com.messaging.domain.dto.response.MessageResponse;
import com.messaging.domain.dto.response.ReactionResponse;
import com.messaging.domain.dto.response.UserResponse;
import com.messaging.domain.entity.*;
import com.messaging.infrastructure.repository.*;
import com.messaging.presentation.exception.BadRequestException;
import com.messaging.presentation.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MessageServiceImpl - Implements message and chat operations
 */
@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    private final ChatRepository chatRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final DeliveryStatusRepository deliveryStatusRepository;
    private final ReadReceiptRepository readReceiptRepository;
    private final GroupRepository groupRepository;
    private final AttachmentRepository attachmentRepository;
    private final MessageReactionRepository messageReactionRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final PushNotificationService pushNotificationService;

    @Override
    @Transactional
    public ChatResponse getOrCreateChat(Long userId, Long otherUserId) {
        if (userId.equals(otherUserId)) {
            throw new BadRequestException("You cannot start a chat with yourself");
        }
        
        User user1 = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        User user2 = userRepository.findById(otherUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + otherUserId));

        // Order users by ID to ensure single chat row per pair
        User firstUser = userId < otherUserId ? user1 : user2;
        User secondUser = userId < otherUserId ? user2 : user1;

        Chat chat = chatRepository.findChatBetweenUsers(firstUser.getId(), secondUser.getId())
                .orElseGet(() -> {
                    Chat newChat = Chat.builder()
                            .user1(firstUser)
                            .user2(secondUser)
                            .isPinned(false)
                            .isArchived(false)
                            .build();
                    return chatRepository.save(newChat);
                });

        return mapToChatResponse(chat, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatResponse> getUserChats(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return chatRepository.findUserChats(userId, pageable)
                .getContent()
                .stream()
                .map(chat -> mapToChatResponse(chat, userId))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public MessageResponse sendMessage(Long senderId, MessageRequest request) {
        User sender = userRepository.findById(senderId)
                .orElseThrow(() -> new ResourceNotFoundException("Sender not found"));

        Chat chat = null;
        Group group = null;

        if (request.getGroupId() != null) {
            group = groupRepository.findById(request.getGroupId())
                    .orElseThrow(() -> new ResourceNotFoundException("Group not found"));
        } else if (request.getChatId() != null) {
            chat = chatRepository.findById(request.getChatId())
                    .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));
        } else if (request.getRecipientId() != null) {
            ChatResponse chatResponse = getOrCreateChat(senderId, request.getRecipientId());
            chat = chatRepository.findById(chatResponse.getId()).orElse(null);
        } else {
            throw new BadRequestException("Must specify chatId, groupId, or recipientId");
        }

        Message message = Message.builder()
                .chat(chat)
                .group(group)
                .sender(sender)
                .messageText(request.getMessageText())
                .messageType(request.getMessageType())
                .isEdited(false)
                .isDeleted(false)
                .build();

        Message savedMessage = messageRepository.save(message);

        if (request.getAttachmentPath() != null) {
            Attachment attachment = Attachment.builder()
                    .message(savedMessage)
                    .fileName(request.getAttachmentFileName() != null ? request.getAttachmentFileName() : "file")
                    .filePath(request.getAttachmentPath())
                    .fileType(request.getAttachmentFileType())
                    .fileSize(request.getAttachmentFileSize())
                    .mimeType(request.getAttachmentFileType())
                    .isDownloaded(false)
                    .build();
            attachmentRepository.save(attachment);
        }

        // Update last message in the chat
        if (chat != null) {
            chat.setLastMessageId(savedMessage.getId());
            chat.setLastMessageAt(savedMessage.getCreatedAt());
            chatRepository.save(chat);
        }

        // Generate initial delivery status for recipients
        if (chat != null) {
            User recipient = chat.getUser1().getId().equals(senderId) ? chat.getUser2() : chat.getUser1();
            deliveryStatusRepository.save(DeliveryStatus.builder()
                    .message(savedMessage)
                    .recipient(recipient)
                    .status(DeliveryStatus.DeliveryStatusType.PENDING)
                    .build());

            // Trigger push notification for private chat
            pushNotificationService.sendPushNotification(
                    recipient.getId(),
                    getUserDisplayName(sender),
                    savedMessage.getMessageText(),
                    Map.of(
                            "chatId", chat.getId().toString(),
                            "type", "CHAT_MESSAGE"
                    )
            );
        } else if (group != null) {
            // Trigger push notification for all other group members
            List<GroupMember> members = groupMemberRepository.findByGroupId(group.getId());
            for (GroupMember member : members) {
                if (!member.getUser().getId().equals(senderId)) {
                    pushNotificationService.sendPushNotification(
                            member.getUser().getId(),
                            group.getGroupName() + " (" + getUserDisplayName(sender) + ")",
                            savedMessage.getMessageText(),
                            Map.of(
                                    "groupId", group.getId().toString(),
                                    "type", "GROUP_MESSAGE"
                            )
                    );
                }
            }
        }

        return mapToMessageResponse(savedMessage);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageResponse> getChatMessages(Long chatId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return messageRepository.findChatMessages(chatId, pageable)
                .getContent()
                .stream()
                .map(this::mapToMessageResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageResponse> getGroupMessages(Long groupId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return messageRepository.findGroupMessages(groupId, pageable)
                .getContent()
                .stream()
                .map(this::mapToMessageResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void markChatAsRead(Long userId, Long chatId) {
        // Query all messages in this chat sent by the other participant that have not been read by this user
        List<Message> unreadMessages = messageRepository.findChatMessages(chatId, Pageable.unpaged())
                .getContent()
                .stream()
                .filter(m -> !m.getSender().getId().equals(userId))
                .filter(m -> readReceiptRepository.findByMessageIdAndUserId(m.getId(), userId).isEmpty())
                .collect(Collectors.toList());

        for (Message message : unreadMessages) {
            readReceiptRepository.save(ReadReceipt.builder()
                    .message(message)
                    .user(userRepository.getReferenceById(userId))
                    .build());
        }
    }

    @Override
    @Transactional
    public void markMessageAsDelivered(Long userId, Long messageId) {
        Optional<DeliveryStatus> optionalStatus = deliveryStatusRepository
                .findByMessageIdAndRecipientId(messageId, userId);

        if (optionalStatus.isPresent()) {
            DeliveryStatus status = optionalStatus.get();
            if (status.getStatus() == DeliveryStatus.DeliveryStatusType.PENDING) {
                status.setStatus(DeliveryStatus.DeliveryStatusType.DELIVERED);
                status.setDeliveredAt(LocalDateTime.now());
                deliveryStatusRepository.save(status);
            }
        }
    }

    private ChatResponse mapToChatResponse(Chat chat, Long currentUserId) {
        User otherUser = chat.getUser1().getId().equals(currentUserId) ? chat.getUser2() : chat.getUser1();
        UserResponse otherUserDto = mapToUserResponse(otherUser);

        MessageResponse lastMessageDto = null;
        if (chat.getLastMessageId() != null) {
            lastMessageDto = messageRepository.findById(chat.getLastMessageId())
                    .map(this::mapToMessageResponse)
                    .orElse(null);
        }

        int unreadCount = readReceiptRepository.countUnreadMessagesInChat(chat.getId(), currentUserId);

        return ChatResponse.builder()
                .id(chat.getId())
                .otherUser(otherUserDto)
                .lastMessage(lastMessageDto)
                .isPinned(chat.getIsPinned())
                .isArchived(chat.getIsArchived())
                .unreadCount(unreadCount)
                .build();
    }

    private MessageResponse mapToMessageResponse(Message message) {
        List<Attachment> attachments = attachmentRepository.findByMessageId(message.getId());
        String attachmentPath = null;
        String attachmentFileName = null;
        String attachmentFileType = null;
        Long attachmentFileSize = null;
        if (attachments != null && !attachments.isEmpty()) {
            Attachment att = attachments.get(0);
            attachmentPath = att.getFilePath();
            attachmentFileName = att.getFileName();
            attachmentFileType = att.getFileType();
            attachmentFileSize = att.getFileSize();
        }

        List<ReactionResponse> reactionsList = messageReactionRepository.findByMessageId(message.getId())
                .stream()
                .map(r -> ReactionResponse.builder()
                        .userId(r.getUser().getId())
                        .username(r.getUser().getUsername())
                        .reaction(r.getReaction())
                        .build())
                .collect(Collectors.toList());

        return MessageResponse.builder()
                .id(message.getId())
                .chatId(message.getChat() != null ? message.getChat().getId() : null)
                .groupId(message.getGroup() != null ? message.getGroup().getId() : null)
                .senderId(message.getSender().getId())
                .senderUsername(message.getSender().getUsername())
                .messageText(message.getMessageText())
                .messageType(message.getMessageType())
                .isEdited(message.getIsEdited())
                .editedAt(message.getEditedAt())
                .isDeleted(message.getIsDeleted())
                .createdAt(message.getCreatedAt())
                .attachmentPath(attachmentPath)
                .attachmentFileName(attachmentFileName)
                .attachmentFileType(attachmentFileType)
                .attachmentFileSize(attachmentFileSize)
                .reactions(reactionsList)
                .build();
    }

    private UserResponse mapToUserResponse(User user) {
        UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElse(null);
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .displayName(profile != null ? profile.getDisplayName() : user.getUsername())
                .bio(profile != null ? profile.getBio() : null)
                .profilePictureUrl(profile != null ? profile.getProfilePictureUrl() : null)
                .status(profile != null && profile.getStatus() != null ? profile.getStatus().name() : "OFFLINE")
                .lastSeen(profile != null ? profile.getLastSeen() : null)
                .isVerified(profile != null ? profile.getIsVerified() : false)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    @Override
    @Transactional
    public MessageResponse editMessage(Long userId, Long messageId, String newText) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));
        if (!message.getSender().getId().equals(userId)) {
            throw new BadRequestException("You can only edit your own messages");
        }
        message.setMessageText(newText);
        message.setIsEdited(true);
        message.setEditedAt(LocalDateTime.now());
        return mapToMessageResponse(messageRepository.save(message));
    }

    @Override
    @Transactional
    public MessageResponse deleteMessage(Long userId, Long messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));
        if (!message.getSender().getId().equals(userId)) {
            throw new BadRequestException("You can only delete your own messages");
        }
        message.setIsDeleted(true);
        message.setMessageText("This message was deleted");
        return mapToMessageResponse(messageRepository.save(message));
    }

    @Override
    @Transactional
    public void addOrUpdateReaction(Long userId, Long messageId, String reaction) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));
        User user = userRepository.getReferenceById(userId);
        
        MessageReaction messageReaction = messageReactionRepository.findByMessageIdAndUserId(messageId, userId)
                .orElseGet(() -> MessageReaction.builder()
                        .message(message)
                        .user(user)
                        .build());
        
        messageReaction.setReaction(reaction);
        messageReactionRepository.save(messageReaction);
    }

    @Override
    @Transactional
    public void removeReaction(Long userId, Long messageId) {
        messageReactionRepository.findByMessageIdAndUserId(messageId, userId)
                .ifPresent(messageReactionRepository::delete);
    }

    @Override
    @Transactional
    public ChatResponse togglePinChat(Long userId, Long chatId, boolean pinned) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));
        chat.setIsPinned(pinned);
        return mapToChatResponse(chatRepository.save(chat), userId);
    }

    @Override
    @Transactional
    public ChatResponse toggleArchiveChat(Long userId, Long chatId, boolean archived) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));
        chat.setIsArchived(archived);
        return mapToChatResponse(chatRepository.save(chat), userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageResponse> searchMessages(Long userId, String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return messageRepository.searchMessages(userId, query, pageable)
                .getContent()
                .stream()
                .map(this::mapToMessageResponse)
                .collect(Collectors.toList());
    }

    private String getUserDisplayName(User user) {
        return userProfileRepository.findByUserId(user.getId())
                .map(UserProfile::getDisplayName)
                .orElse(user.getUsername());
    }
}
