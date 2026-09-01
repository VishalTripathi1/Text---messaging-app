package com.messaging.data.model.response

/**
 * GroupResponse - Remote API response DTO for group chats
 */
data class GroupResponse(
    val id: Long,
    val groupName: String,
    val description: String?,
    val groupImageUrl: String?,
    val createdByUserId: Long?,
    val createdAt: String,
    val memberCount: Long,
    val members: List<UserResponse> = emptyList()
)
