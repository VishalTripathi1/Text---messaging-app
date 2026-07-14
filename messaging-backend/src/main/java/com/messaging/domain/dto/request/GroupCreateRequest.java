package com.messaging.domain.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * GroupCreateRequest - Request payload for creating a new group chat
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupCreateRequest {
    private String groupName;
    private String description;
    private List<Long> memberUserIds;
}
