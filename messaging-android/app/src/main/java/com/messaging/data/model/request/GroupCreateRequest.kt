package com.messaging.data.model.request

/**
 * GroupCreateRequest - Request payload to create a new group chat
 */
data class GroupCreateRequest(
    val groupName: String,
    val description: String,
    val memberUserIds: List<Long>
)
