package com.messaging.util

/**
 * Constants - Application-wide configuration parameters
 */
object Constants {

    /**
     * SERVER_HOST:
     * - For Physical Personal Devices: Use your Mac's Wi-Fi IP address (e.g., "10.13.177.107").
     * - For Android Studio Emulators: "10.0.2.2" or your Mac's Wi-Fi IP address ("10.13.177.107") both work.
     */
    const val SERVER_HOST = "10.13.177.107"
    const val SERVER_PORT = "8080"

    val BASE_URL = "http://$SERVER_HOST:$SERVER_PORT/api/"
    val WS_BASE_URL = "ws://$SERVER_HOST:$SERVER_PORT/ws"
    val ATTACHMENT_BASE_URL = "http://$SERVER_HOST:$SERVER_PORT/api"
}
