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
                    // Two requests for the same pair arriving together - a double tap on a search
                    // result, or both participants opening the conversation at the same moment -
                    // each miss the lookup above and each try to insert. The ID ordering only
                    // keeps the columns consistent; on its own it does not stop a second row, so
                    // the pair ended up with two chats and each device wrote its messages into a
                    // different one. insertIfAbsent leans on the database's unique index to let
                    // whichever request loses the race quietly no-op, then both re-read the one
                    // surviving row. The re-read cannot come back empty: ON CONFLICT waits on the
                    // other transaction and sees its row once it commits.
                    chatRepository.insertIfAbsent(firstUser.getId(), secondUser.getId());
                    return chatRepository.findChatBetweenUsers(firstUser.getId(), secondUser.getId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Chat row absent immediately after insert for users "
                                            + firstUser.getId() + " and " + secondUser.getId()));
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
        return mapToChatResponses(
                chatRepository.findUserChatsExcludingArchived(userId, pageable).getContent(), userId);
    }

    @Override
    @Transactional
    public List<ChatResponse> getArchivedChats(Long userId, int page, int size) {
        // Archiving a chat previously made it vanish permanently - there was no endpoint to list
        // archived chats at all, so there was no way to ever find or unarchive one again.
        //
        // Not readOnly, for the same reason getUserChats isn't: mapToChatResponses can save() a chat
        // to backfill a missing lastMessageId, and a write in a read-only transaction either silently
        // does nothing or throws, depending on the driver.
        Pageable pageable = PageRequest.of(page, size);
        return mapToChatResponses(
                chatRepository.findArchivedUserChats(userId, pageable).getContent(), userId);
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
                        notificationText(savedMessage),
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
                            notificationText(savedMessage),
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
        // mapToMessageResponses, not one mapToMessageResponse per row: the per-message version ran
        // about five queries each, which is why a long history took seconds to come back.
        return mapToMessageResponses(messageRepository.findChatMessages(chatId, pageable).getContent());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageResponse> getGroupMessages(Long userId, Long groupId, int page, int size) {
        if (!messageRepository.isUserInGroup(groupId, userId)) {
            throw new BadRequestException("You are not a member of this group");
        }
        Pageable pageable = PageRequest.of(page, size);
        return mapToMessageResponses(messageRepository.findGroupMessages(groupId, pageable).getContent());
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
        return mapToChatResponses(List.of(chat), currentUserId).get(0);
    }

    /**
     * Maps the chat list with a fixed number of queries instead of roughly nine per chat.
     *
     * <p>Opening the app was slow for the same reason opening a conversation was: each chat looked up
     * the other participant's profile, then its last message, then that message's attachment,
     * reactions, read receipt, delivery status and the recipient's read-receipt setting, then its own
     * unread count and pin/archive row. Twenty chats came to something like 180 sequential round trips
     * to a remote database before the first row could be drawn.
     *
     * <p>All of it is now fetched per page: the profiles together, the last messages together (and
     * mapped by {@link #mapToMessageResponses}, which batches internally), the unread counts as one
     * grouped query, the preferences as one more.
     */
    private List<ChatResponse> mapToChatResponses(List<Chat> chats, Long currentUserId) {
        if (chats == null || chats.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        List<Long> chatIds = chats.stream().map(Chat::getId).collect(Collectors.toList());

        // Which participant is "the other one", read straight off the chats' foreign-key columns:
        // chat.getUser1().getId() would initialise two lazy proxies per chat instead.
        Map<Long, Long> otherUserIdByChatId = new java.util.HashMap<>();
        for (Object[] row : chatRepository.findParticipantIdRowsByChatIds(chatIds)) {
            Long user1Id = (Long) row[1];
            Long user2Id = (Long) row[2];
            otherUserIdByChatId.put((Long) row[0], currentUserId.equals(user1Id) ? user2Id : user1Id);
        }

        Map<Long, User> userById = userRepository
                .findAllById(new java.util.HashSet<>(otherUserIdByChatId.values()))
                .stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        Map<Long, User> otherUserByChatId = new java.util.HashMap<>();
        for (Chat chat : chats) {
            Long otherUserId = otherUserIdByChatId.get(chat.getId());
            User otherUser = otherUserId != null ? userById.get(otherUserId) : null;
            if (otherUser == null) {
                // Nothing should be able to get here - the ids came from these very rows - but a chat
                // whose participant went missing is not worth failing the whole list over.
                otherUser = chat.getUser1().getId().equals(currentUserId) ? chat.getUser2() : chat.getUser1();
            }
            otherUserByChatId.put(chat.getId(), otherUser);
        }

        java.util.Set<Long> otherUserIds = otherUserByChatId.values().stream()
                .map(User::getId)
                .collect(Collectors.toSet());
        Map<Long, UserProfile> profileByUserId = new java.util.HashMap<>();
        for (Object[] row : userProfileRepository.findProfileRowsByUserIds(otherUserIds)) {
            profileByUserId.put((Long) row[0], (UserProfile) row[1]);
        }

        // Last messages: one findAllById for every chat that already knows its lastMessageId, then a
        // single-row lookup only for the chats that don't. That backfill path stays per-chat because
        // it also writes the id back, and it runs at most once per chat ever.
        List<Long> knownLastMessageIds = chats.stream()
                .map(Chat::getLastMessageId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        List<Message> lastMessages = knownLastMessageIds.isEmpty()
                ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(messageRepository.findAllById(knownLastMessageIds));

        for (Chat chat : chats) {
            boolean resolved = chat.getLastMessageId() != null
                    && lastMessages.stream().anyMatch(m -> m.getId().equals(chat.getLastMessageId()));
            if (resolved) {
                continue;
            }
            // Either the chat never recorded a lastMessageId, or it points at a row that no longer
            // exists. Both are repaired here, as before.
            List<Message> latest = messageRepository
                    .findChatMessages(chat.getId(), PageRequest.of(0, 1)).getContent();
            if (!latest.isEmpty()) {
                Message latestMsg = latest.get(0);
                lastMessages.add(latestMsg);
                chat.setLastMessageId(latestMsg.getId());
                chat.setLastMessageAt(latestMsg.getCreatedAt());
                chatRepository.save(chat);
            }
        }

        // One batch for every last message on the page, however it was found.
        Map<Long, MessageResponse> lastMessageById = new java.util.HashMap<>();
        for (MessageResponse response : mapToMessageResponses(lastMessages)) {
            lastMessageById.put(response.getId(), response);
        }

        Map<Long, Integer> unreadByChatId = new java.util.HashMap<>();
        for (Object[] row : readReceiptRepository.countUnreadMessagesInChats(chatIds, currentUserId)) {
            unreadByChatId.put((Long) row[0], ((Number) row[1]).intValue());
        }

        // Pin/archive state is per-user (see ChatUserPreference) - a chat pinned or archived by
        // one participant must not affect what the other participant sees. Absence of a
        // preference row (the common case) means "not pinned, not archived", and the batch query
        // simply returns no row for those chats.
        Map<Long, Boolean> pinnedByChatId = new java.util.HashMap<>();
        Map<Long, Boolean> archivedByChatId = new java.util.HashMap<>();
        for (Object[] row : chatUserPreferenceRepository
                .findPreferenceRowsByUserIdAndChatIds(currentUserId, chatIds)) {
            pinnedByChatId.put((Long) row[0], Boolean.TRUE.equals(row[1]));
            archivedByChatId.put((Long) row[0], Boolean.TRUE.equals(row[2]));
        }

        List<ChatResponse> responses = new java.util.ArrayList<>(chats.size());
        for (Chat chat : chats) {
            User otherUser = otherUserByChatId.get(chat.getId());
            MessageResponse lastMessageDto = chat.getLastMessageId() != null
                    ? lastMessageById.get(chat.getLastMessageId())
                    : null;

            responses.add(ChatResponse.builder()
                    .id(chat.getId())
                    .otherUser(mapToUserResponse(otherUser, profileByUserId.get(otherUser.getId())))
                    .lastMessage(lastMessageDto)
                    .isPinned(Boolean.TRUE.equals(pinnedByChatId.get(chat.getId())))
                    .isArchived(Boolean.TRUE.equals(archivedByChatId.get(chat.getId())))
                    .unreadCount(unreadByChatId.getOrDefault(chat.getId(), 0))
                    .build());
        }

        return responses;
    }

    private MessageResponse mapToMessageResponse(Message message) {
        // Deliberately delegates rather than keeping a second implementation: every field, and in
        // particular the delivery-status rules, must read identically whether one message or a whole
        // page is being mapped. A batch of one costs the same handful of queries this method used to
        // run inline.
        return mapToMessageResponses(List.of(message)).get(0);
    }

    /**
     * Maps a whole page of messages with a fixed number of queries instead of a handful per message.
     *
     * <p>This is what made opening a conversation slow. Mapped one at a time, each message ran its own
     * attachment lookup, reaction lookup, read-receipt lookup, delivery-status lookup and settings
     * lookup - about five round trips each, so a hundred-message page was some five hundred queries
     * against a remote Postgres, and a group message multiplied the receipt and delivery lookups by
     * the number of members again. None of it was slow in itself; it was all latency, several hundred
     * sequential round trips of it, which is why history took seconds to appear while the message
     * text had been sitting in the database the whole time.
     *
     * <p>Everything the page needs is now fetched up front, once, keyed by message id, and the
     * per-message work is pure in-memory lookup. Settings and group membership are memoised across
     * the page because a conversation has one recipient or one member list, not one per message.
     */
    private List<MessageResponse> mapToMessageResponses(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        List<Long> messageIds = messages.stream().map(Message::getId).collect(Collectors.toList());

        // First attachment per message - findAttachmentRowsByMessageIds orders by id, and putIfAbsent
        // therefore keeps the same one the old attachments.get(0) picked.
        Map<Long, Object[]> attachmentByMessageId = new java.util.HashMap<>();
        for (Object[] row : attachmentRepository.findAttachmentRowsByMessageIds(messageIds)) {
            attachmentByMessageId.putIfAbsent((Long) row[0], row);
        }

        Map<Long, List<ReactionResponse>> reactionsByMessageId = new java.util.HashMap<>();
        for (Object[] row : messageReactionRepository.findReactionRowsByMessageIds(messageIds)) {
            reactionsByMessageId
                    .computeIfAbsent((Long) row[0], id -> new java.util.ArrayList<>())
                    .add(ReactionResponse.builder()
                            .userId((Long) row[1])
                            .username((String) row[2])
                            .reaction((String) row[3])
                            .build());
        }

        Map<Long, java.util.Set<Long>> readersByMessageId = new java.util.HashMap<>();
        for (Object[] row : readReceiptRepository.findReaderIdsByMessageIds(messageIds)) {
            readersByMessageId
                    .computeIfAbsent((Long) row[0], id -> new java.util.HashSet<>())
                    .add((Long) row[1]);
        }

        Map<Long, java.util.Set<Long>> deliveredToByMessageId = new java.util.HashMap<>();
        for (Object[] row : deliveryStatusRepository.findRecipientIdsByMessageIdsAndStatus(
                messageIds, DeliveryStatus.DeliveryStatusType.DELIVERED)) {
            deliveredToByMessageId
                    .computeIfAbsent((Long) row[0], id -> new java.util.HashSet<>())
                    .add((Long) row[1]);
        }

        // Who each private message was sent to, resolved once. Both chat participants are lazy
        // associations, so this dereferences at most one chat and two users per conversation on the
        // page rather than per message - and it lets every read-receipt setting below be fetched in a
        // single query instead of one per recipient.
        Map<Long, Long> recipientIdByMessageId = new java.util.HashMap<>();
        for (Message message : messages) {
            if (message.getChat() != null) {
                Long senderId = message.getSender().getId();
                Long user1Id = message.getChat().getUser1().getId();
                recipientIdByMessageId.put(message.getId(),
                        user1Id.equals(senderId) ? message.getChat().getUser2().getId() : user1Id);
            }
        }

        // showReadReceipts existed as a setting but was never checked - the sender always saw a
        // "read" tick regardless of whether the recipient had disabled read receipts for themselves.
        // Internal unread-count tracking still relies on the ReadReceipt row existing (see
        // countUnreadMessagesInChat) so we keep creating it either way - only the status *reported
        // back to the sender* is capped at DELIVERED when the reader opted out, which is the actual
        // privacy behavior this setting is meant to provide.
        Map<Long, Boolean> showsReadReceiptsByUserId = new java.util.HashMap<>();
        if (!recipientIdByMessageId.isEmpty()) {
            for (Object[] row : userSettingsRepository.findShowReadReceiptsRowsByUserIds(
                    new java.util.HashSet<>(recipientIdByMessageId.values()))) {
                showsReadReceiptsByUserId.put((Long) row[0], !Boolean.FALSE.equals(row[1]));
            }
        }

        // Memoised rather than batched: a page belongs to one group in every case except a search
        // across conversations, so this is a single query in practice. Cached unfiltered, because
        // different members send different messages and each one has to drop its own sender.
        Map<Long, List<Long>> memberIdsByGroupId = new java.util.HashMap<>();

        List<MessageResponse> responses = new java.util.ArrayList<>(messages.size());
        for (Message message : messages) {
            Long messageId = message.getId();

            String attachmentPath = null;
            String attachmentFileName = null;
            String attachmentFileType = null;
            Long attachmentFileSize = null;

            if (!Boolean.TRUE.equals(message.getIsDeleted())) {
                Object[] attachment = attachmentByMessageId.get(messageId);
                if (attachment != null) {
                    attachmentPath = (String) attachment[1];
                    attachmentFileName = (String) attachment[2];
                    attachmentFileType = (String) attachment[3];
                    attachmentFileSize = (Long) attachment[4];
                }
            }

            List<ReactionResponse> reactionsList =
                    reactionsByMessageId.getOrDefault(messageId, java.util.Collections.emptyList());

            java.util.Set<Long> readers =
                    readersByMessageId.getOrDefault(messageId, java.util.Collections.emptySet());
            java.util.Set<Long> deliveredTo =
                    deliveredToByMessageId.getOrDefault(messageId, java.util.Collections.emptySet());

            Long senderId = message.getSender().getId();
            String statusStr = "SENT";

            Long recipientId = recipientIdByMessageId.get(messageId);
            if (recipientId != null) {
                // Absent from the map means no settings row at all, which is the default: enabled.
                boolean recipientShowsReadReceipts =
                        !Boolean.FALSE.equals(showsReadReceiptsByUserId.get(recipientId));

                if (readers.contains(recipientId) && recipientShowsReadReceipts) {
                    statusStr = "READ";
                } else if (deliveredTo.contains(recipientId)) {
                    statusStr = "DELIVERED";
                }
            } else if (message.getGroup() != null) {
                // Group messages previously always defaulted to "SENT" - mapToMessageResponse only
                // ever computed a real status for private chats (message.getChat() != null). Now
                // that sendMessage() also creates DeliveryStatus rows per group member, aggregate
                // across every other member: READ only once everyone has read it, DELIVERED once
                // everyone has received it, otherwise SENT. This mirrors how WhatsApp-style group
                // ticks behave; a full per-member read breakdown is a separate, larger feature.
                //
                // The membership is cached for the whole page but the sender is filtered per message,
                // since different members send different messages in the same group.
                List<Long> allMemberIds = memberIdsByGroupId.computeIfAbsent(
                        message.getGroup().getId(),
                        groupMemberRepository::findMemberIdsByGroupId);

                List<Long> otherMemberIds = allMemberIds.stream()
                        .filter(id -> !id.equals(senderId))
                        .collect(Collectors.toList());

                if (!otherMemberIds.isEmpty()) {
                    if (readers.containsAll(otherMemberIds)) {
                        statusStr = "READ";
                    } else if (deliveredTo.containsAll(otherMemberIds)) {
                        statusStr = "DELIVERED";
                    }
                }
            }

            responses.add(MessageResponse.builder()
                    .id(message.getId())
                    .chatId(message.getChat() != null ? message.getChat().getId() : null)
                    .groupId(message.getGroup() != null ? message.getGroup().getId() : null)
                    .senderId(senderId)
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
                    .build());
        }

        return responses;
    }

    private UserResponse mapToUserResponse(User user) {
        return mapToUserResponse(user, userProfileRepository.findByUserId(user.getId()).orElse(null));
    }

    /**
     * Builds the user DTO from a profile the caller already has, so a list of chats doesn't run one
     * profile lookup per row. {@code profile} may be null - a user without a profile row falls back to
     * their username, exactly as before.
     */
    private UserResponse mapToUserResponse(User user, UserProfile profile) {
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
        if (message.getMessageType() != Message.MessageType.TEXT ||
                !attachmentRepository.findByMessageId(messageId).isEmpty()) {
            throw new BadRequestException("Photo and voice messages cannot be edited");
        }
        if (message.getCreatedAt() == null ||
                message.getCreatedAt().isBefore(LocalDateTime.now().minusHours(24))) {
            throw new BadRequestException("Messages can only be edited within 24 hours");
        }
        if (newText == null || newText.trim().isEmpty() || newText.length() > 5000) {
            throw new BadRequestException("Edited message must contain 1 to 5000 characters");
        }
        message.setMessageText(newText);
        message.setIsEdited(true);
        message.setEditedAt(LocalDateTime.now());
        Message savedMessage = messageRepository.save(message);

        // An edit replaces what the message says, so anyone who had already read it has now read
        // something that is no longer there - the edit has to come back to their attention the same
        // way a new message would.
        //
        // Dropping their read receipts is the whole mechanism. Unread counts are *derived* from the
        // absence of a receipt (see ReadReceiptRepository.countUnreadMessagesInChat), so removing
        // the row makes the message unread again with no extra state and no schema change, and
        // markChatAsRead() recreates it when they next open the conversation.
        //
        // Deleted one at a time through the existing finder rather than as a bulk JPQL delete, so the
        // removals go through the persistence context and Hibernate's own caches stay consistent with
        // the database. Recipient counts here are one (private) or the group's membership, so the
        // extra queries are cheap. mapToMessageResponses() below reads receipts with a JPQL query,
        // which auto-flushes these deletes first and therefore sees the message as unread again -
        // which is the point: the sender's tick has to drop back out of "read".
        List<Long> recipientIds = otherParticipantIds(savedMessage, userId);
        for (Long recipientId : recipientIds) {
            readReceiptRepository.findByMessageIdAndUserId(savedMessage.getId(), recipientId)
                    .ifPresent(readReceiptRepository::delete);
        }

        MessageResponse response = mapToMessageResponse(savedMessage);
        // Editing a deleted message leaves it reading "This message was deleted" on every client,
        // so there is nothing worth waking anyone up for.
        if (!Boolean.TRUE.equals(savedMessage.getIsDeleted())) {
            pushMessageChange(savedMessage, userId, recipientIds, "MESSAGE_EDITED",
                    "Edited: " + excerpt(newText));
        }
        return response;
    }

    /**
     * Everybody in this message's conversation other than {@code actorId}: the other participant of
     * a private chat, or every group member but the actor.
     */
    private List<Long> otherParticipantIds(Message message, Long actorId) {
        if (message.getChat() != null) {
            Chat chat = message.getChat();
            Long otherId = chat.getUser1().getId().equals(actorId)
                    ? chat.getUser2().getId() : chat.getUser1().getId();
            return List.of(otherId);
        }
        if (message.getGroup() != null) {
            return groupMemberRepository.findMemberIdsByGroupId(message.getGroup().getId())
                    .stream()
                    .filter(id -> !id.equals(actorId))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    /**
     * Pushes a change to an existing message (an edit, a reaction) to the people who should hear
     * about it.
     *
     * <p>Deliberately mirrors the two push blocks in {@link #sendMessage}: a private-chat push is
     * titled with the actor's display name and is suppressed when the recipient has archived the
     * chat, which is how muting works here; a group push is titled "&lt;group&gt; (&lt;actor&gt;)"
     * and carries {@code groupId} instead of {@code chatId} so the client can resolve it to the
     * negative chat id it addresses groups by. {@code type} tells the client which conversation
     * field to read and is matched in MessagingFirebaseService.onMessageReceived.
     *
     * <p>The recipient's own notifications-enabled setting is enforced one level down, inside
     * {@link PushNotificationService#sendPushNotification}.
     */
    private void pushMessageChange(Message message, Long actorId, List<Long> recipientIds,
                                   String type, String body) {
        User actor = userRepository.findById(actorId).orElse(null);
        if (actor == null) {
            return;
        }
        Chat chat = message.getChat();
        Group group = message.getGroup();
        for (Long recipientId : recipientIds) {
            if (recipientId.equals(actorId)) {
                continue;
            }
            String title;
            Map<String, String> data;
            if (chat != null) {
                boolean recipientArchivedThisChat = chatUserPreferenceRepository
                        .findByChatIdAndUserId(chat.getId(), recipientId)
                        .map(p -> Boolean.TRUE.equals(p.getIsArchived()))
                        .orElse(false);
                if (recipientArchivedThisChat) {
                    continue;
                }
                title = getUserDisplayName(actor);
                data = Map.of("chatId", chat.getId().toString(), "type", type);
            } else if (group != null) {
                title = group.getGroupName() + " (" + getUserDisplayName(actor) + ")";
                data = Map.of("groupId", group.getId().toString(), "type", type);
            } else {
                continue;
            }
            pushNotificationService.sendPushNotification(recipientId, title, body, data);
        }
    }

    /**
     * A one-line, notification-sized version of a message body. Message text runs to 5000
     * characters and an FCM data payload is capped at 4KB, so quoting it whole risks the push being
     * rejected outright.
     */
    private String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 79) + "…";
    }

    private String notificationText(Message message) {
        return switch (message.getMessageType()) {
            case IMAGE -> "Photo";
            case VOICE -> "Voice message";
            case VIDEO -> "Video";
            case DOCUMENT -> "Document";
            default -> excerpt(message.getMessageText());
        };
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
        MessageResponse response = mapToMessageResponse(messageRepository.save(message));
        response.setDeliveryStatus(null);
        List<Long> recipientIds = otherParticipantIds(message, userId);
        pushMessageChange(message, userId, recipientIds, "MESSAGE_DELETED", "This message was deleted");
        return response;
    }

    @Override
    @Transactional
    public MessageResponse addOrUpdateReaction(Long userId, Long messageId, String reaction) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));
        ensureConversationMember(message, userId);
        User user = userRepository.getReferenceById(userId);
        
        MessageReaction messageReaction = messageReactionRepository.findByMessageIdAndUserId(messageId, userId)
                .orElseGet(() -> MessageReaction.builder()
                        .message(message)
                        .user(user)
                        .build());
        
        messageReaction.setReaction(reaction);
        messageReactionRepository.save(messageReaction);

        // Only the message's author is told, in private chats and groups alike. Everyone else in a
        // group has no stake in who reacted to somebody else's message, and telling them turns one
        // popular message into a push per member per reaction.
        //
        // No unread bump to go with it, unlike an edit: a reaction doesn't change what the message
        // says, so a badge promising something new to read would be a lie. Removing a reaction is
        // silent too - see removeReaction.
        Long authorId = message.getSender().getId();
        boolean reactingToOwnMessage = authorId.equals(userId);
        if (!reactingToOwnMessage && !Boolean.TRUE.equals(message.getIsDeleted())) {
            pushMessageChange(message, userId, List.of(authorId), "MESSAGE_REACTION",
                    "Reacted " + reaction + " to: " + excerpt(message.getMessageText()));
        }
        return mapToMessageResponse(message);
    }

    @Override
    @Transactional
    public MessageResponse removeReaction(Long userId, Long messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));
        ensureConversationMember(message, userId);
        messageReactionRepository.findByMessageIdAndUserId(messageId, userId)
                .ifPresent(messageReactionRepository::delete);
        return mapToMessageResponse(message);
    }

    /**
     * Rejects anyone who is not in the conversation this message belongs to.
     *
     * <p>The reaction endpoints took a message id straight from the URL and never checked it, the
     * same hole {@link #getChatMessages} had: any authenticated user could react to any message just
     * by guessing an id. Harmless-looking until a reaction started notifying the message's author -
                    .messageText(notificationText(request.getMessageText()))
     * walking the id space. Reuses the isUserInChat/isUserInGroup queries already in
     * MessageRepository.
     */
    private void ensureConversationMember(Message message, Long userId) {
        if (message.getChat() != null) {
            if (!messageRepository.isUserInChat(message.getChat().getId(), userId)) {
                throw new BadRequestException("You are not a participant of this chat");
            }
        } else if (message.getGroup() != null) {
            if (!messageRepository.isUserInGroup(message.getGroup().getId(), userId)) {
                throw new BadRequestException("You are not a member of this group");
            }
        }
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
        return mapToMessageResponses(
                messageRepository.searchMessages(userId, query, pageable).getContent());
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
