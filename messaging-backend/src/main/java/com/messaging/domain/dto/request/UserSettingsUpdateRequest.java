package com.messaging.domain.dto.request;

import lombok.*;

/**
 * UserSettingsUpdateRequest - DTO for updating user settings preference flags
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSettingsUpdateRequest {

    private String theme;
    private Boolean notificationsEnabled;
    private Boolean soundEnabled;
    private Boolean vibrationEnabled;
    private Boolean showReadReceipts;
    private Boolean showTypingIndicator;
}
