package com.messaging.domain.dto.response;

import lombok.*;

/**
 * UserSettingsResponse - DTO for returning user settings info
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSettingsResponse {

    private Long id;
    private Long userId;
    private String theme;
    private Boolean notificationsEnabled;
    private Boolean soundEnabled;
    private Boolean vibrationEnabled;
    private Boolean showReadReceipts;
    private Boolean showTypingIndicator;
}
