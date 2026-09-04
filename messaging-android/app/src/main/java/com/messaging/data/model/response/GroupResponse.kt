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
    val members: List<GroupMemberResponse> = emptyList(),
    val isAdmin: Boolean = false,
    val isCleared: Boolean = false,
    val isDeleted: Boolean = false
)

/**
 * A group member's user info paired with their role - the data model
 * (GroupMember.MemberRole on the backend) already had admin/member roles, they just weren't
 * exposed through the API before, which is why the app had no way to show or act on them.
 */
data class GroupMemberResponse(
    val user: UserResponse,
    val isAdmin: Boolean = false
)
