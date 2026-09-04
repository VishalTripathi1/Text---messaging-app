package com.messaging.data.model.request

/**
 * GroupUpdateRequest - Request payload for renaming a group or changing its description.
 * Either field can be left null to leave it unchanged.
 */
data class GroupUpdateRequest(
    val groupName: String? = null,
    val description: String? = null
)
