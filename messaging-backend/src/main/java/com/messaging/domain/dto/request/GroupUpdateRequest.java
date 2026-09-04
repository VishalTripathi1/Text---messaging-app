package com.messaging.domain.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GroupUpdateRequest - Request payload for renaming a group or changing its description.
 * Either field may be null/omitted to leave that field unchanged.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupUpdateRequest {
    private String groupName;
    private String description;
}
