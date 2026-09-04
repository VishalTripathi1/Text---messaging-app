package com.messaging.infrastructure.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.messaging.MainActivity
import com.messaging.domain.repository.ChatRepository
import com.messaging.util.TokenManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Inject

/**
 * MessagingFirebaseService - Handles Firebase Cloud Messaging token updates and push delivery
 */
@AndroidEntryPoint
class MessagingFirebaseService : FirebaseMessagingService() {

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var tokenManager: TokenManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Timber.d("New FCM Token: $token")
        registerTokenOnBackend(token)
    }

    private fun registerTokenOnBackend(token: String) {
        val deviceName = Build.MODEL
        val osVersion = Build.VERSION.RELEASE
        
        serviceScope.launch {
            // Same stable id as login (TokenManager.getOrCreateDeviceId) - see the comment on
            // that function for why ANDROID_ID here and a login-time UUID disagreeing with each
            // other caused sessions to look inconsistent across the app's own device-facing calls.
            val deviceId = tokenManager.getOrCreateDeviceId()
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
            // An edited message and a reaction can land on either kind of conversation, so the
            // backend sends whichever id applies (see MessageServiceImpl.pushMessageChange) and we
            // follow the one that is present. Without these two cases the push still appeared but
            // fell through to `else -> null`, which means no deep link *and* no Room sync - the
            // notification would open the app to whatever screen it started on and the edit would
            // not be visible until some later sync happened to pick it up.
            "MESSAGE_EDITED", "MESSAGE_REACTION", "MESSAGE_DELETED" ->
                remoteMessage.data["chatId"]?.toLongOrNull()
                    ?: remoteMessage.data["groupId"]?.toLongOrNull()?.let { -it }
                    ?: remoteMessage.data["groupId"]?.toLongOrNull()?.let { -it }
            else -> null
        }
        Timber.d("Push type=$type deepLinkChatId=$deepLinkChatId data=${remoteMessage.data}")

        // runBlocking, not serviceScope.launch: onMessageReceived already runs on an FCM background
        // thread, and the OS may tear this service down as soon as it returns. Posting the
        // notification from a coroutine would race that teardown and silently drop alerts. The three
        // reads are local DataStore lookups, so the block is short.
        //
        // The Room sync below deliberately ignores these settings: turning notifications off should
        // silence the alert, not stop messages from arriving.
        val (notificationsEnabled, soundEnabled, vibrationEnabled) = runBlocking {
            Triple(
                tokenManager.notificationsEnabled.first(),
                tokenManager.soundEnabled.first(),
                tokenManager.vibrationEnabled.first()
            )
        }
        if (notificationsEnabled) {
            showNotification(
                title = title,
                body = body,
                deepLinkChatId = deepLinkChatId,
                soundEnabled = soundEnabled,
                vibrationEnabled = vibrationEnabled
            )
        } else {
            Timber.d("Notifications disabled in settings - suppressing alert for chat $deepLinkChatId")
        }

        // Pull the message into the local database. A push is the only delivery route while the
        // app is backgrounded - the OS drops the WebSocket, so the server's sendToUserStatic()
        // fails and it falls back to FCM (see ChatController.notifyMessageUpdate). Nothing here
        // touched Room, so the message existed only server-side: the notification appeared, but
        // the chat list preview and the conversation itself stayed stale until some screen's
        // ON_RESUME happened to sync. syncMessages() takes the same negative-id convention for
        // groups that deepLinkChatId already uses, so it can be passed straight through.
        if (deepLinkChatId != null) {
            serviceScope.launch {
                // The backend dispatches FCM while the message transaction is still completing.
                // The first request can therefore arrive a few milliseconds before the new row is
                // visible. Retry briefly so attachment-only messages also appear in the inbox
                // without requiring the receiver to open the conversation.
                repeat(3) { attempt ->
                    chatRepository.syncChats()
                    chatRepository.syncMessages(deepLinkChatId)
                        .onFailure { Timber.e(it, "Failed to sync messages for chat $deepLinkChatId after push (attempt ${attempt + 1})") }
                    if (attempt < 2) kotlinx.coroutines.delay(1500)
                }
            }
        }
    }

    /**
     * Returns the channel id matching the current sound/vibration preferences, creating that channel
     * the first time the combination is used.
     *
     * Four possible channels rather than one, because from Android 8 sound and vibration are
     * properties of the channel, not of the individual notification: the builder's setSound() and
     * setVibrate() are ignored, and createNotificationChannel() silently declines to change those
     * fields on a channel id that already exists. A single fixed channel therefore can never be
     * quietened after it has been created once - which is precisely why the Play Sounds and Vibrate
     * switches appeared to do nothing on a device that had already received one notification.
     * Deleting and recreating the channel is not a way around it either: Android remembers the
     * settings of deleted channels and restores them when the same id reappears.
     *
     * Channels are created lazily, so someone who never touches these switches still sees a single
     * entry for the app under the system notification settings.
     */
    private fun resolveChannel(
        manager: NotificationManager,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean
    ): String {
        val channelId = "chat_messages" +
                (if (soundEnabled) "_sound" else "_silent") +
                (if (vibrationEnabled) "_vibrate" else "_still")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return channelId

        // The original single channel is left over on devices that ran an earlier build. It is no
        // longer posted to, so remove it rather than leaving a dead entry in system settings.
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)

        if (manager.getNotificationChannel(channelId) == null) {
            val channelName = when {
                soundEnabled && vibrationEnabled -> "Chat Messages"
                soundEnabled -> "Chat Messages (no vibration)"
                vibrationEnabled -> "Chat Messages (vibrate only)"
                else -> "Chat Messages (silent)"
            }
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Displays incoming chat messages"
                enableVibration(vibrationEnabled)
                if (!soundEnabled) {
                    setSound(null, null)
                }
            }
            manager.createNotificationChannel(channel)
        }
        return channelId
    }

    @SuppressLint("NotificationPermission")
    private fun showNotification(
        title: String,
        body: String,
        deepLinkChatId: Long?,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = resolveChannel(notificationManager, soundEnabled, vibrationEnabled)

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
            .apply {
                // minSdk is 24, so Android 7 is still supported and there are no channels there -
                // on those versions sound and vibration come from the notification itself, and
                // setDefaults is the only thing that honours the two switches. On Android 8+ this
                // is ignored in favour of the channel resolved above.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    var defaults = 0
                    if (soundEnabled) defaults = defaults or NotificationCompat.DEFAULT_SOUND
                    if (vibrationEnabled) defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
                    setDefaults(defaults)
                }
            }
            .build()

        notificationManager.notify(notificationId, notification)
    }

    companion object {
        /** Channel id used before sound/vibration became selectable. See resolveChannel(). */
        private const val LEGACY_CHANNEL_ID = "chat_messages_channel"
    }
}
