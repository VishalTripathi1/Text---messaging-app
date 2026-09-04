package com.messaging

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.google.firebase.messaging.FirebaseMessaging
import com.messaging.domain.repository.ChatRepository
import com.messaging.presentation.navigation.NavGraph
import com.messaging.presentation.navigation.PendingDeepLink
import com.messaging.presentation.theme.MessagingTheme
import com.messaging.util.TokenManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import timber.log.Timber
import javax.inject.Inject

/**
 * MainActivity - Main entry point for the Android application
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_DEEP_LINK_CHAT_ID = "deep_link_chat_id"
        private const val NO_DEEP_LINK = Long.MIN_VALUE
    }

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var tokenManager: TokenManager

    // Holds a chat to navigate to once startup routing (splash -> auth check) has settled. Read
    // from the launch intent below, and refreshed via onNewIntent() for the case where the app is
    // already running and the user taps another notification.
    private val pendingDeepLink = mutableStateOf<PendingDeepLink?>(null)

    // Each tap gets its own nonce. Two notifications for the same chat would otherwise write an
    // identical Long into the state above, Compose would see no change, and the navigation
    // effect in NavGraph would never re-run - so the second tap would silently do nothing.
    private var deepLinkNonce = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        // Register only after an authenticated token exists. On a fresh install the previous
        // eager call ran before login, received 401, and was never retried; the server then had
        // no device token to notify. Re-registering after a refresh is harmless (upsert).
        lifecycleScope.launch {
            tokenManager.accessToken.filterNotNull().collect {
                registerFcmToken()
            }
        }

        readDeepLinkFromIntent(intent)

        setContent {
            val themeSetting by tokenManager.theme.collectAsState(initial = "SYSTEM")
            val useDarkTheme = when (themeSetting) {
                "DARK" -> true
                "LIGHT" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            val deepLink by pendingDeepLink
            MessagingTheme(darkTheme = useDarkTheme) {
                val navController = rememberNavController()
                NavGraph(
                    navController = navController,
                    deepLink = deepLink,
                    onDeepLinkHandled = { pendingDeepLink.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDeepLinkFromIntent(intent)
    }

    private fun readDeepLinkFromIntent(intent: Intent?) {
        intent ?: return
        val chatId = intent.getLongExtra(EXTRA_DEEP_LINK_CHAT_ID, NO_DEEP_LINK)
        if (chatId == NO_DEEP_LINK) return
        Timber.d("Deep link intent: chatId=$chatId")

        // Consume the extra. It lives on the activity's intent, which survives configuration
        // changes and process restore, so leaving it in place makes every later onCreate() replay
        // the same deep link and yank the user out of whatever screen they were on.
        intent.removeExtra(EXTRA_DEEP_LINK_CHAT_ID)
        pendingDeepLink.value = PendingDeepLink(chatId = chatId, nonce = deepLinkNonce++)
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
                
                val deviceName = Build.MODEL
                val osVersion = Build.VERSION.RELEASE
                
                lifecycleScope.launch {
                    // Same stable id as login (TokenManager.getOrCreateDeviceId), not
                    // ANDROID_ID - registerDevice() and login() used to disagree on what "this
                    // device" was, which meant this call created/matched a *different* Device
                    // row on the backend than the one the current session was actually tied to.
                    val deviceId = tokenManager.getOrCreateDeviceId()
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
