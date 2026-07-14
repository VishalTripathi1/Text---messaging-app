package com.messaging.application.service;

import com.messaging.domain.dto.request.GroupCreateRequest;
import com.messaging.domain.dto.response.GroupResponse;
import com.messaging.domain.entity.Group;
import com.messaging.domain.entity.GroupMember;
import com.messaging.domain.entity.User;
import com.messaging.infrastructure.repository.GroupMemberRepository;
import com.messaging.infrastructure.repository.GroupRepository;
import com.messaging.infrastructure.repository.UserRepository;
import com.messaging.presentation.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
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

        return mapToResponse(savedGroup);
    }

    @Override
    public List<GroupResponse> getUserGroups(Long userId, int page, int size) {
        return groupRepository.findUserGroups(userId, PageRequest.of(page, size)).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public GroupResponse getGroupDetails(Long groupId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found"));
        return mapToResponse(group);
    }

    @Override
    @Transactional
    public void addMembers(Long groupId, List<Long> userIds) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Group not found"));

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
    public void removeMember(Long groupId, Long userId) {
        groupMemberRepository.deleteByGroupIdAndUserId(groupId, userId);
    }

    private GroupResponse mapToResponse(Group group) {
        long memberCount = groupRepository.countMembers(group.getId());
        return GroupResponse.builder()
                .id(group.getId())
                .groupName(group.getGroupName())
                .description(group.getDescription())
                .groupImageUrl(group.getGroupImageUrl())
                .createdByUserId(group.getCreatedBy() != null ? group.getCreatedBy().getId() : null)
                .createdAt(group.getCreatedAt())
                .memberCount(memberCount)
                .build();
    }
}
