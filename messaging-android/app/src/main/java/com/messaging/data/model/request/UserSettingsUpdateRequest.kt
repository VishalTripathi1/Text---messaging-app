package com.messaging.data.model.request

/**
 * UserSettingsUpdateRequest - Request model to update user settings preferences
 */
data class UserSettingsUpdateRequest(
    val theme: String?,
    val notificationsEnabled: Boolean?,
    val soundEnabled: Boolean?,
    val vibrationEnabled: Boolean?,
    val showReadReceipts: Boolean?,
    val showTypingIndicator: Boolean?
)
