package com.messaging.application.service;

import com.messaging.domain.entity.Group;
import com.messaging.domain.entity.GroupMember;
import com.messaging.domain.entity.User;
import com.messaging.domain.entity.Chat;
import com.messaging.infrastructure.repository.*;
import com.messaging.presentation.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageServiceImplTest {

    @Mock private ChatRepository chatRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private DeliveryStatusRepository deliveryStatusRepository;
    @Mock private ReadReceiptRepository readReceiptRepository;
    @Mock private GroupRepository groupRepository;
    @Mock private AttachmentRepository attachmentRepository;
    @Mock private MessageReactionRepository messageReactionRepository;
    @Mock private GroupMemberRepository groupMemberRepository;
    @Mock private ChatUserPreferenceRepository chatUserPreferenceRepository;
    @Mock private UserSettingsRepository userSettingsRepository;
    @Mock private BlockedUserRepository blockedUserRepository;
    @Mock private PushNotificationService pushNotificationService;

    @InjectMocks
    private MessageServiceImpl messageService;

    @Test
    void deleteChat_groupAdminShouldBeAllowedToDeleteGroup() {
        Long adminId = 2L;
        Long groupId = 5L;
        User creator = User.builder().id(1L).username("creator").build();
        User admin = User.builder().id(adminId).username("admin").build();
        Group group = Group.builder().id(groupId).groupName("Ops").createdBy(creator).build();
        GroupMember adminMembership = GroupMember.builder().group(group).user(admin).role(GroupMember.MemberRole.ADMIN).build();
        Chat chat = Chat.builder().id(groupId)
                .user1(creator)
                .user2(admin)
                .build();

        when(groupRepository.findById(groupId)).thenReturn(Optional.of(group));
        when(chatRepository.findById(groupId)).thenReturn(Optional.of(chat));
        when(groupMemberRepository.findByGroupIdAndUserId(groupId, adminId)).thenReturn(Optional.of(adminMembership));
        when(chatUserPreferenceRepository.findByChatIdAndUserId(groupId, adminId)).thenReturn(Optional.empty());
        when(userRepository.getReferenceById(adminId)).thenReturn(admin);

        assertDoesNotThrow(() -> messageService.deleteChat(adminId, -groupId));

        // Should create and save a ChatUserPreference with deleted=true
        verify(chatUserPreferenceRepository).findByChatIdAndUserId(groupId, adminId);
        verify(chatUserPreferenceRepository).save(argThat(preference ->
            preference.getChat().getId().equals(groupId) &&
            preference.getUser().getId().equals(adminId) &&
            preference.getDeleted() == true));

        // Should NOT delete messages or group
        verify(messageRepository, never()).deleteByGroupId(anyLong());
        verify(groupRepository, never()).delete(any());
    }
}
