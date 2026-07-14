package com.messaging.domain.dto.request;

import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * UserProfileUpdateRequest - DTO for updating user profile info
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfileUpdateRequest {

    @Size(max = 100, message = "Display name must be less than 100 characters")
    private String displayName;

    private String bio;

    @Size(max = 500, message = "Profile picture URL must be less than 500 characters")
    private String profilePictureUrl;
}
