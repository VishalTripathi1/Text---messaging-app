package com.messaging.domain.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;

/**
 * GroupResponse - Response payload representing group details and metadata
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupResponse {
    private Long id;
    private String groupName;
    private String description;
    private String groupImageUrl;
    private Long createdByUserId;
    private LocalDateTime createdAt;
    private long memberCount;
    private List<UserResponse> members;
}
