package com.messaging.domain.dto.response;

import lombok.*;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * AuthResponse DTO - Authentication response with tokens
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {

    private Long userId;
    private String username;
    private String email;
    private String accessToken;
    private String refreshToken;
    private Long expiresIn;
    private String tokenType = "Bearer";
}
