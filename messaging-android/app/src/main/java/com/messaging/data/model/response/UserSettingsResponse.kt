package com.messaging.data.model.response

/**
 * UserSettingsResponse - Response model for user settings preferences
 */
data class UserSettingsResponse(
    val id: Long,
    val userId: Long,
    val theme: String?,
    val notificationsEnabled: Boolean?,
    val soundEnabled: Boolean?,
    val vibrationEnabled: Boolean?,
    val showReadReceipts: Boolean?,
    val showTypingIndicator: Boolean?
)
