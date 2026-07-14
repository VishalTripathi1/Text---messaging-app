package com.messaging.domain.entity

/**
 * Reaction - Domain entity representing an emoji reaction from a user
 */
data class Reaction(
    val userId: Long,
    val username: String,
    val reaction: String
)
