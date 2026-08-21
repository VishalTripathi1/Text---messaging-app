package com.messaging.infrastructure.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.messaging.MainActivity
import com.messaging.domain.repository.ChatRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * MessagingFirebaseService - Handles Firebase Cloud Messaging token updates and push delivery
 */
@AndroidEntryPoint
class MessagingFirebaseService : FirebaseMessagingService() {

    @Inject
    lateinit var chatRepository: ChatRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Timber.d("New FCM Token: $token")
        registerTokenOnBackend(token)
    }

    private fun registerTokenOnBackend(token: String) {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_android"
        val deviceName = Build.MODEL
        val osVersion = Build.VERSION.RELEASE
        
        serviceScope.launch {
            chatRepository.registerDevice(
                deviceId = deviceId,
                deviceName = deviceName,
                deviceType = "ANDROID",
                osVersion = osVersion,
                appVersion = "1.0.0",
                fcmToken = token
            ).onSuccess {
                Timber.d("FCM Token successfully registered on backend")
            }.onFailure {
                Timber.e(it, "Failed to register FCM Token on backend")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Timber.d("Received message from: ${remoteMessage.from}")

        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: "New Message"
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: ""

        // The backend already includes chatId/groupId in the FCM data payload (see
        // MessageServiceImpl.sendMessage) but this was never read on the client, so tapping a
        // notification just opened the app to whatever screen it happened to start on instead
        // of the specific chat the message belongs to. Groups are addressed as a negative
        // chatId throughout the app (see ChatMapper) so we mirror that convention here.
        val type = remoteMessage.data["type"]
        val deepLinkChatId: Long? = when (type) {
            "CHAT_MESSAGE" -> remoteMessage.data["chatId"]?.toLongOrNull()
            "GROUP_MESSAGE" -> remoteMessage.data["groupId"]?.toLongOrNull()?.let { -it }
            else -> null
        }

        showNotification(title, body, deepLinkChatId)
    }

    @SuppressLint("NotificationPermission")
    private fun showNotification(title: String, body: String, deepLinkChatId: Long?) {
        val channelId = "chat_messages_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Chat Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Displays incoming chat messages"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (deepLinkChatId != null) {
                putExtra(MainActivity.EXTRA_DEEP_LINK_CHAT_ID, deepLinkChatId)
            }
        }
        // The requestCode was previously hardcoded to 0 for every notification, which meant
        // every notification's PendingIntent could be conflated as "the same" by the system -
        // tapping any of them could resolve to a stale intent from a different chat rather than
        // the one that was actually tapped. Using the chatId (falling back to a value derived
        // from the current time so unrelated notifications still get distinct request codes)
        // keeps each notification's PendingIntent genuinely unique.
        val requestCode = deepLinkChatId?.hashCode() ?: System.currentTimeMillis().toInt()
        val pendingIntent = PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
