package com.messaging.data.model.request

/**
 * DeviceRegisterRequest - DTO representing a request to register a device token
 */
data class DeviceRegisterRequest(
    val deviceId: String,
    val deviceName: String?,
    val deviceType: String?,
    val osVersion: String?,
    val appVersion: String?,
    val fcmToken: String
)
