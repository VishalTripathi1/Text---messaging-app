package com.messaging.application.service;

import com.messaging.domain.dto.request.GroupCreateRequest;
import com.messaging.domain.dto.response.GroupResponse;
import java.util.List;

/**
 * GroupService - Service layer interface for group chat actions and operations
 */
public interface GroupService {
    GroupResponse createGroup(Long creatorId, GroupCreateRequest request);
    
    List<GroupResponse> getUserGroups(Long userId, int page, int size);
    
    GroupResponse getGroupDetails(Long groupId);
    
    void addMembers(Long groupId, List<Long> userIds);
    
    void removeMember(Long groupId, Long userId);
}
