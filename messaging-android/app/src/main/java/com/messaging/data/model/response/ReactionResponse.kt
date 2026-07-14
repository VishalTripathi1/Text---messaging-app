package com.messaging.data.model.response

/**
 * ReactionResponse - DTO for emoji reaction payloads
 */
data class ReactionResponse(
    val userId: Long,
    val username: String,
    val reaction: String
)
