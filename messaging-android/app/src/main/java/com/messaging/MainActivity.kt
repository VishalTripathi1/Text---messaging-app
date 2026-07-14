package com.messaging

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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * MainActivity - Main entry point for the Android application
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var chatRepository: ChatRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        // Safely fetch and register FCM push token
        registerFcmToken()

        setContent {
            MessagingTheme {
                val navController = rememberNavController()
                NavGraph(navController = navController)
            }
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
