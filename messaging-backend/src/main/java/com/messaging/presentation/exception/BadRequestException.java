package com.messaging.presentation.exception;

/**
 * BadRequestException - Thrown when a request has invalid arguments or violates domain rules
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }

    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
