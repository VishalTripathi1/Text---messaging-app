package com.messaging

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.google.firebase.messaging.FirebaseMessaging
import com.messaging.domain.repository.ChatRepository
import com.messaging.presentation.navigation.NavGraph
import com.messaging.presentation.theme.MessagingTheme
import com.messaging.util.TokenManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * MainActivity - Main entry point for the Android application
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_DEEP_LINK_CHAT_ID = "deep_link_chat_id"
    }

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var tokenManager: TokenManager

    // Holds a chat to navigate straight to once the app has finished its normal startup
    // routing (splash -> auth check). Read from the launch intent below, and refreshed via
    // onNewIntent() for the case where the app is already running and the user taps another
    // notification.
    private val deepLinkChatId = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        // Safely fetch and register FCM push token
        registerFcmToken()

        readDeepLinkFromIntent(intent)

        setContent {
            val themeSetting by tokenManager.theme.collectAsState(initial = "SYSTEM")
            val useDarkTheme = when (themeSetting) {
                "DARK" -> true
                "LIGHT" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            val pendingChatId by deepLinkChatId
            MessagingTheme(darkTheme = useDarkTheme) {
                val navController = rememberNavController()
                NavGraph(navController = navController, deepLinkChatId = pendingChatId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDeepLinkFromIntent(intent)
    }

    private fun readDeepLinkFromIntent(intent: Intent?) {
        val chatId = intent?.getLongExtra(EXTRA_DEEP_LINK_CHAT_ID, Long.MIN_VALUE) ?: Long.MIN_VALUE
        if (chatId != Long.MIN_VALUE) {
            deepLinkChatId.value = chatId
        }
    }

    private fun registerFcmToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Timber.w(task.exception, "Fetching FCM registration token failed")
                    return@addOnCompleteListener
                }
                val token = task.result
                Timber.d("FCM Token: $token")
                
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_android"
                val deviceName = Build.MODEL
                val osVersion = Build.VERSION.RELEASE
                
                lifecycleScope.launch {
                    chatRepository.registerDevice(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        deviceType = "ANDROID",
                        osVersion = osVersion,
                        appVersion = "1.0.0",
                        fcmToken = token
                    ).onSuccess {
                        Timber.d("Successfully registered FCM Token in MainActivity")
                    }.onFailure {
                        Timber.e(it, "Failed to register FCM Token in MainActivity")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Firebase Messaging is not configured or google-services.json is missing. Skipping token registration.")
        }
    }
}
