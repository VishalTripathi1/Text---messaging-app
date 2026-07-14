package com.messaging.domain.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ReactionResponse - DTO representing a single message reaction details
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReactionResponse {
    private Long userId;
    private String username;
    private String reaction;
}
