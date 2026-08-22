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
    private final ChatUserPreferenceRepository chatUserPreferenceRepository;
    private final UserSettingsRepository userSettingsRepository;
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
                            .build();
                    return chatRepository.save(newChat);
                });

        return mapToChatResponse(chat, userId);
    }

    @Override
    @Transactional
    public List<ChatResponse> getUserChats(Long userId, int page, int size) {
        // This was previously @Transactional(readOnly = true), but mapToChatResponse() below
        // can call chatRepository.save(chat) to backfill a missing lastMessageId - a write
        // inside a read-only transaction, which silently no-ops or throws depending on the JDBC
        // driver. Since mapToChatResponse now also does the real work of resolving per-user
        // pin/archive state, this method genuinely needs write access.
        Pageable pageable = PageRequest.of(page, size);
        return chatRepository.findUserChatsExcludingArchived(userId, pageable)
                .getContent()
                .stream()
                .map(chat -> mapToChatResponse(chat, userId))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatResponse> getArchivedChats(Long userId, int page, int size) {
        // Archiving a chat previously made it vanish permanently - there was no endpoint to list
        // archived chats at all, so there was no way to ever find or unarchive one again.
        Pageable pageable = PageRequest.of(page, size);
        return chatRepository.findArchivedUserChats(userId, pageable)
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

        // No validation existed at all: empty/blank text messages were accepted and there was
        // no length cap (a client bug or malicious request could store an arbitrarily large
        // blob). messageType itself is already a strongly-typed enum (Message.MessageType), so
        // Jackson rejects any value outside {TEXT, IMAGE, VIDEO, DOCUMENT, VOICE, REACTION}
        // before this method is ever reached - no separate check needed for that part.
        if (request.getMessageType() == null) {
            throw new BadRequestException("messageType is required");
        }
        if (request.getMessageType() == Message.MessageType.TEXT) {
            if (request.getMessageText() == null || request.getMessageText().trim().isEmpty()) {
                throw new BadRequestException("Message text cannot be empty");
            }
        }
        if (request.getMessageText() != null && request.getMessageText().length() > 5000) {
            throw new BadRequestException("Message text cannot exceed 5000 characters");
        }

        Chat chat = null;
        Group group = null;

        if (request.getGroupId() != null) {
            group = groupRepository.findById(request.getGroupId())
                    .orElseThrow(() -> new ResourceNotFoundException("Group not found"));
            if (groupMemberRepository.findByGroupIdAndUserId(group.getId(), senderId).isEmpty()) {
                throw new BadRequestException("You are not a member of this group");
            }
        } else if (request.getChatId() != null) {
            chat = chatRepository.findById(request.getChatId())
                    .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));
            ensureChatParticipant(chat, senderId);
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

            // Archived chats should be silenced, the same way muting works in every other chat
            // app - previously a push fired unconditionally regardless of archive state.
            boolean recipientArchivedThisChat = chatUserPreferenceRepository
                    .findByChatIdAndUserId(chat.getId(), recipient.getId())
                    .map(p -> Boolean.TRUE.equals(p.getIsArchived()))
                    .orElse(false);
            if (!recipientArchivedThisChat) {
                pushNotificationService.sendPushNotification(
                        recipient.getId(),
                        getUserDisplayName(sender),
                        savedMessage.getMessageText(),
                        Map.of(
                                "chatId", chat.getId().toString(),
                                "type", "CHAT_MESSAGE"
                        )
                );
            }
        } else if (group != null) {
            // Delivery-status tracking for group messages previously didn't exist at all - only
            // private-chat messages got a DeliveryStatus row, so mapToMessageResponse() had no
            // way to compute anything but the hardcoded default "SENT" for every group message,
            // and group ticks never progressed to DELIVERED/READ.
            List<GroupMember> members = groupMemberRepository.findByGroupId(group.getId());
            for (GroupMember member : members) {
                if (!member.getUser().getId().equals(senderId)) {
                    deliveryStatusRepository.save(DeliveryStatus.builder()
                            .message(savedMessage)
                            .recipient(member.getUser())
                            .status(DeliveryStatus.DeliveryStatusType.PENDING)
                            .build());

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
    public List<MessageResponse> getChatMessages(Long userId, Long chatId, int page, int size) {
        // These endpoints previously took chatId/groupId straight from the URL with no check
        // that the caller was actually a participant - any authenticated user could read any
        // chat's or group's message history just by guessing/incrementing the ID. The
        // isUserInChat/isUserInGroup queries already existed in MessageRepository but were
        // never called from anywhere.
        if (!messageRepository.isUserInChat(chatId, userId)) {
            throw new BadRequestException("You are not a participant of this chat");
        }
        Pageable pageable = PageRequest.of(page, size);
        return messageRepository.findChatMessages(chatId, pageable)
                .getContent()
                .stream()
                .map(this::mapToMessageResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageResponse> getGroupMessages(Long userId, Long groupId, int page, int size) {
        if (!messageRepository.isUserInGroup(groupId, userId)) {
            throw new BadRequestException("You are not a member of this group");
        }
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
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));
        ensureChatParticipant(chat, userId);
        List<Message> unreadMessages = messageRepository.findUnreadMessagesInChat(chatId, userId);

        for (Message message : unreadMessages) {
            // The query excludes existing receipts, but retain this guard so concurrent REST
            // and WebSocket read events cannot violate the database uniqueness constraint.
            if (readReceiptRepository.findByMessageIdAndUserId(message.getId(), userId).isEmpty()) {
                readReceiptRepository.save(ReadReceipt.builder()
                        .message(message)
                        .user(userRepository.getReferenceById(userId))
                        .build());
            }
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

        if (lastMessageDto == null) {
            List<Message> latestMessages = messageRepository.findChatMessages(chat.getId(), PageRequest.of(0, 1)).getContent();
            if (!latestMessages.isEmpty()) {
                Message latestMsg = latestMessages.get(0);
                lastMessageDto = mapToMessageResponse(latestMsg);
                chat.setLastMessageId(latestMsg.getId());
                chat.setLastMessageAt(latestMsg.getCreatedAt());
                chatRepository.save(chat);
            }
        }

        int unreadCount = readReceiptRepository.countUnreadMessagesInChat(chat.getId(), currentUserId);

        // Pin/archive state is per-user (see ChatUserPreference) - a chat pinned or archived by
        // one participant must not affect what the other participant sees. Absence of a
        // preference row (the common case) means "not pinned, not archived".
        ChatUserPreference preference = chatUserPreferenceRepository
                .findByChatIdAndUserId(chat.getId(), currentUserId)
                .orElse(null);
        boolean isPinned = preference != null && Boolean.TRUE.equals(preference.getIsPinned());
        boolean isArchived = preference != null && Boolean.TRUE.equals(preference.getIsArchived());

        return ChatResponse.builder()
                .id(chat.getId())
                .otherUser(otherUserDto)
                .lastMessage(lastMessageDto)
                .isPinned(isPinned)
                .isArchived(isArchived)
                .unreadCount(unreadCount)
                .build();
    }

    private MessageResponse mapToMessageResponse(Message message) {
        String attachmentPath = null;
        String attachmentFileName = null;
        String attachmentFileType = null;
        Long attachmentFileSize = null;

        if (!Boolean.TRUE.equals(message.getIsDeleted())) {
            List<Attachment> attachments = attachmentRepository.findByMessageId(message.getId());
            if (attachments != null && !attachments.isEmpty()) {
                Attachment att = attachments.get(0);
                attachmentPath = att.getFilePath();
                attachmentFileName = att.getFileName();
                attachmentFileType = att.getFileType();
                attachmentFileSize = att.getFileSize();
            }
        }

        List<ReactionResponse> reactionsList = messageReactionRepository.findByMessageId(message.getId())
                .stream()
                .map(r -> ReactionResponse.builder()
                        .userId(r.getUser().getId())
                        .username(r.getUser().getUsername())
                        .reaction(r.getReaction())
                        .build())
                .collect(Collectors.toList());

        String statusStr = "SENT";
        if (message.getChat() != null) {
            Long recipientId = message.getChat().getUser1().getId().equals(message.getSender().getId()) ?
                    message.getChat().getUser2().getId() : message.getChat().getUser1().getId();

            // showReadReceipts existed as a setting but was never checked - the sender always
            // saw a "read" tick regardless of whether the recipient had disabled read receipts
            // for themselves. Internal unread-count tracking still relies on the ReadReceipt row
            // existing (see countUnreadMessagesInChat) so we keep creating it either way -  only
            // the status *reported back to the sender* is capped at DELIVERED when the reader
            // opted out, which is the actual privacy behavior this setting is meant to provide.
            boolean recipientShowsReadReceipts = userSettingsRepository.findByUserId(recipientId)
                    .map(s -> !Boolean.FALSE.equals(s.getShowReadReceipts()))
                    .orElse(true);

            boolean hasRead = readReceiptRepository.findByMessageIdAndUserId(message.getId(), recipientId).isPresent();
            if (hasRead && recipientShowsReadReceipts) {
                statusStr = "READ";
            } else {
                Optional<DeliveryStatus> ds = deliveryStatusRepository.findByMessageIdAndRecipientId(message.getId(), recipientId);
                if (ds.isPresent() && ds.get().getStatus() == DeliveryStatus.DeliveryStatusType.DELIVERED) {
                    statusStr = "DELIVERED";
                }
            }
        } else if (message.getGroup() != null) {
            // Group messages previously always defaulted to "SENT" - mapToMessageResponse only
            // ever computed a real status for private chats (message.getChat() != null). Now
            // that sendMessage() also creates DeliveryStatus rows per group member, aggregate
            // across every other member: READ only once everyone has read it, DELIVERED once
            // everyone has received it, otherwise SENT. This mirrors how WhatsApp-style group
            // ticks behave; a full per-member read breakdown is a separate, larger feature.
            List<Long> otherMemberIds = groupMemberRepository.findByGroupId(message.getGroup().getId())
                    .stream()
                    .map(m -> m.getUser().getId())
                    .filter(id -> !id.equals(message.getSender().getId()))
                    .toList();

            if (!otherMemberIds.isEmpty()) {
                boolean allRead = otherMemberIds.stream().allMatch(id ->
                        readReceiptRepository.findByMessageIdAndUserId(message.getId(), id).isPresent());
                if (allRead) {
                    statusStr = "READ";
                } else {
                    boolean allDelivered = otherMemberIds.stream().allMatch(id ->
                            deliveryStatusRepository.findByMessageIdAndRecipientId(message.getId(), id)
                                    .map(ds -> ds.getStatus() == DeliveryStatus.DeliveryStatusType.DELIVERED)
                                    .orElse(false));
                    if (allDelivered) {
                        statusStr = "DELIVERED";
                    }
                }
            }
        }

        return MessageResponse.builder()
                .id(message.getId())
                .chatId(message.getChat() != null ? message.getChat().getId() : null)
                .groupId(message.getGroup() != null ? message.getGroup().getId() : null)
                .senderId(message.getSender().getId())
                .senderUsername(message.getSender().getUsername())
                // Always derive the tombstone text from the deletion flag. This also repairs
                // any historical rows that were marked deleted before the message text update
                // was introduced, so every client sees the same placeholder after a sync.
                .messageText(Boolean.TRUE.equals(message.getIsDeleted()) ? "This message was deleted" : message.getMessageText())
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
                .deliveryStatus(statusStr)
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
        if (newText == null || newText.trim().isEmpty() || newText.length() > 5000) {
            throw new BadRequestException("Edited message must contain 1 to 5000 characters");
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
        // The entity has a deletedAt column but nothing ever populated it - it silently stayed
        // null forever regardless of when (or whether) a message was actually deleted.
        message.setDeletedAt(java.time.LocalDateTime.now());
        // Retain the message row as a deletion placeholder but remove its attachment metadata so
        // neither client can expose a deleted file through an old cached response.
        attachmentRepository.deleteAll(attachmentRepository.findByMessageId(messageId));
        return mapToMessageResponse(messageRepository.save(message));
    }

    @Override
    @Transactional
    public MessageResponse addOrUpdateReaction(Long userId, Long messageId, String reaction) {
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
        return mapToMessageResponse(message);
    }

    @Override
    @Transactional
    public MessageResponse removeReaction(Long userId, Long messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));
        messageReactionRepository.findByMessageIdAndUserId(messageId, userId)
                .ifPresent(messageReactionRepository::delete);
        return mapToMessageResponse(message);
    }

    @Override
    @Transactional
    public ChatResponse togglePinChat(Long userId, Long chatId, boolean pinned) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));
        ensureChatParticipant(chat, userId);
        ChatUserPreference preference = chatUserPreferenceRepository.findByChatIdAndUserId(chatId, userId)
                .orElseGet(() -> ChatUserPreference.builder().chat(chat).user(userRepository.getReferenceById(userId)).build());
        preference.setIsPinned(pinned);
        chatUserPreferenceRepository.save(preference);
        return mapToChatResponse(chat, userId);
    }

    @Override
    @Transactional
    public ChatResponse toggleArchiveChat(Long userId, Long chatId, boolean archived) {
        Chat chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat not found"));
        ensureChatParticipant(chat, userId);
        ChatUserPreference preference = chatUserPreferenceRepository.findByChatIdAndUserId(chatId, userId)
                .orElseGet(() -> ChatUserPreference.builder().chat(chat).user(userRepository.getReferenceById(userId)).build());
        preference.setIsArchived(archived);
        chatUserPreferenceRepository.save(preference);
        return mapToChatResponse(chat, userId);
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

    private void ensureChatParticipant(Chat chat, Long userId) {
        if (!chat.getUser1().getId().equals(userId) && !chat.getUser2().getId().equals(userId)) {
            throw new BadRequestException("You are not a participant of this chat");
        }
    }
}
