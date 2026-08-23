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
        Timber.d("Push type=$type deepLinkChatId=$deepLinkChatId data=${remoteMessage.data}")

        showNotification(title, body, deepLinkChatId)

        // Pull the message into the local database. A push is the only delivery route while the
        // app is backgrounded - the OS drops the WebSocket, so the server's sendToUserStatic()
        // fails and it falls back to FCM (see ChatController.notifyMessageUpdate). Nothing here
        // touched Room, so the message existed only server-side: the notification appeared, but
        // the chat list preview and the conversation itself stayed stale until some screen's
        // ON_RESUME happened to sync. syncMessages() takes the same negative-id convention for
        // groups that deepLinkChatId already uses, so it can be passed straight through.
        if (deepLinkChatId != null) {
            serviceScope.launch {
                chatRepository.syncMessages(deepLinkChatId)
                    .onFailure { Timber.e(it, "Failed to sync messages for chat $deepLinkChatId after push") }
                chatRepository.syncChats()
            }
        }
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
            // FLAG_ACTIVITY_NEW_TASK is required because the intent is launched from a service
            // context, not from an activity. Paired with the manifest's singleTop launchMode it
            // brings the existing task forward and delivers to onNewIntent() rather than
            // building a second copy of MainActivity.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (deepLinkChatId != null) {
                putExtra(MainActivity.EXTRA_DEEP_LINK_CHAT_ID, deepLinkChatId)
            }
        }

        // One notification, and one PendingIntent request code, per conversation. A newer
        // message replaces that chat's existing notification instead of stacking a fresh one,
        // which also guarantees the visible notification always holds the current PendingIntent.
        // Messages with no chat context keep unique ids so they don't collapse into each other.
        val notificationId = deepLinkChatId?.let { (it xor (it ushr 32)).toInt() }
            ?: System.currentTimeMillis().toInt()

        // FLAG_UPDATE_CURRENT, not FLAG_ONE_SHOT. Intent.filterEquals() ignores extras, so a
        // repeat notification for the same chat resolves to the cached PendingIntent - without
        // UPDATE_CURRENT it would keep the older intent's extras. FLAG_ONE_SHOT was worse still:
        // it cancelled the PendingIntent the first time it fired, so once any notification for a
        // chat had been tapped, every other notification for that chat held a dead PendingIntent
        // and tapping it did nothing at all.
        val pendingIntent = PendingIntent.getActivity(
            this, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
