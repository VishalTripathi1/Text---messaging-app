package com.messaging.application.service;

import com.messaging.domain.dto.request.GroupCreateRequest;
import com.messaging.domain.dto.response.GroupResponse;
import com.messaging.domain.dto.response.UserResponse;
import com.messaging.domain.entity.ChatUserPreference;
import com.messaging.domain.entity.Group;
import com.messaging.domain.entity.GroupMember;
import com.messaging.domain.entity.User;
import com.messaging.infrastructure.repository.ChatUserPreferenceRepository;
import com.messaging.infrastructure.repository.GroupMemberRepository;
import com.messaging.infrastructure.repository.GroupRepository;
import com.messaging.infrastructure.repository.UserRepository;
import com.messaging.presentation.exception.BadRequestException;
import com.messaging.presentation.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * GroupServiceImpl - Implementation of GroupService interface
 */
@Service
@RequiredArgsConstructor
public class GroupServiceImpl implements GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final ChatUserPreferenceRepository chatUserPreferenceRepository;

    @Override
    @Transactional
    public GroupResponse createGroup(Long creatorId, GroupCreateRequest request) {
        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> new ResourceNotFoundException("Creator user not found"));

        Group group = Group.builder()
                .groupName(request.getGroupName())
                .description(request.getDescription())
                .createdBy(creator)
                .isActive(true)
                .members(new ArrayList<>())
                .build();

        Group savedGroup = groupRepository.save(group);

        GroupMember creatorMember = GroupMember.builder()
                .group(savedGroup)
                .user(creator)
                .role(GroupMember.MemberRole.ADMIN)
                .build();
        groupMemberRepository.save(creatorMember);
        savedGroup.getMembers().add(creatorMember);

        if (request.getMemberUserIds() != null) {
            for (Long userId : request.getMemberUserIds()) {
                if (userId.equals(creatorId)) continue;
                userRepository.findById(userId).ifPresent(user -> {
                    GroupMember member = GroupMember.builder()
                            .group(savedGroup)
                            .user(user)
                            .role(GroupMember.MemberRole.MEMBER)
                            .build();
                    groupMemberRepository.save(member);
                    savedGroup.getMembers().add(member);
                });
            }
        }

        return mapToResponse(savedGroup, creatorId);
    }

    @Override
    public List<GroupResponse> getUserGroups(Long userId, int page, int size) {
        // Previously always used findUserGroups(), which never looked at ChatUserPreference at
        // all - clearing or deleting a group (see MessageServiceImpl.clearChat/deleteChat) had
        // nowhere to take effect, so the group just stayed in this list forever, unlike a
        // private chat which does disappear once cleared/deleted.
        return groupRepository.findUserGroupsExcludingHidden(userId, PageRequest.of(page, size)).stream()
                .map(group -> mapToResponse(group, userId))
                .collect(Collectors.toList());
    }

    @Override
    public List<GroupResponse> getClearedGroups(Long userId, int page, int size) {
        return groupRepository.findClearedUserGroups(userId, PageRequest.of(page, size)).stream()
                .map(group -> mapToResponse(group, userId))
                .collect(Collectors.toList());
    }

    @Override
    public List<GroupResponse> getDeletedGroups(Long userId, int page, int size) {
        return groupRepository.findDeletedUserGroups(userId, PageRequest.of(page, size)).stream()
                .map(group -> mapToResponse(group, userId))
                .collect(Collectors.toList());
    }

    @Override
    public GroupResponse getGroupDetails(Long requesterId, Long groupId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found"));
        requireMember(groupId, requesterId);
        return mapToResponse(group, requesterId);
    }

    @Override
    @Transactional
    public void addMembers(Long requesterId, Long groupId, List<Long> userIds) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found"));
        requireMember(groupId, requesterId);

        for (Long userId : userIds) {
            if (groupMemberRepository.findByGroupIdAndUserId(groupId, userId).isPresent()) {
                continue;
            }
            userRepository.findById(userId).ifPresent(user -> {
                GroupMember member = GroupMember.builder()
                        .group(group)
                        .user(user)
                        .role(GroupMember.MemberRole.MEMBER)
                        .build();
                groupMemberRepository.save(member);
            });
        }
    }

    @Override
    @Transactional
    public void removeMember(Long requesterId, Long groupId, Long userId) {
        // Any member can remove themselves (leaving the group). Removing someone else
        // requires being an admin of the group.
        if (!requesterId.equals(userId)) {
            GroupMember requesterMembership = requireMember(groupId, requesterId);
            if (requesterMembership.getRole() != GroupMember.MemberRole.ADMIN) {
                throw new BadRequestException("Only group admins can remove other members");
            }
        } else {
            requireMember(groupId, requesterId);
        }
        groupMemberRepository.deleteByGroupIdAndUserId(groupId, userId);
    }

    /**
     * Confirms the given user is actually a member of the group before letting them view its
     * details or manage its membership. Previously these endpoints had no authorization check
     * at all - any authenticated user who knew (or guessed) a groupId could view any group's
     * details, add arbitrary members to it, or remove members from it, regardless of whether
     * they belonged to that group.
     */
    private GroupMember requireMember(Long groupId, Long userId) {
        return groupMemberRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new BadRequestException("You are not a member of this group"));
    }

    private GroupResponse mapToResponse(Group group, Long userId) {
        long memberCount = groupRepository.countMembers(group.getId());
        // Preferences for a group live at -groupId, see clearChat()/deleteChat() in
        // MessageServiceImpl for why the id is negated.
        Optional<ChatUserPreference> preference =
                chatUserPreferenceRepository.findByChatIdAndUserId(-group.getId(), userId);
        return GroupResponse.builder()
                .id(group.getId())
                .groupName(group.getGroupName())
                .description(group.getDescription())
                .groupImageUrl(group.getGroupImageUrl())
                .createdByUserId(group.getCreatedBy() != null ? group.getCreatedBy().getId() : null)
                .createdAt(group.getCreatedAt())
                .memberCount(memberCount)
                .isCleared(preference.map(p -> Boolean.TRUE.equals(p.getIsCleared())).orElse(false))
                .isDeleted(preference.map(p -> Boolean.TRUE.equals(p.getDeleted())).orElse(false))
                .members(group.getMembers() == null ? List.of() : group.getMembers().stream().map(member -> UserResponse.builder()
                    .id(member.getUser().getId())
                    .username(member.getUser().getUsername())
                    .email(member.getUser().getEmail())
                    .displayName(member.getUser().getUsername())
                    .build()).collect(Collectors.toList()))
                .build();
    }
}
