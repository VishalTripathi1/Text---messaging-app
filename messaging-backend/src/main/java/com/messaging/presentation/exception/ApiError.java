package com.messaging.presentation.exception;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * ApiError - Standardized error response format
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiError {

    private int statusCode;
    private String message;
    private String error;
    private LocalDateTime timestamp;
    private String path;

    public static ApiError of(int statusCode, String message, String error, String path) {
        return ApiError.builder()
                .statusCode(statusCode)
                .message(message)
                .error(error)
                .timestamp(LocalDateTime.now())
                .path(path)
                .build();
    }
}
