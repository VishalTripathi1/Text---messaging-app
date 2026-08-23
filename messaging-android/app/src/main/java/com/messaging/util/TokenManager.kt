package com.messaging.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "auth_prefs")

/**
 * TokenManager - Securely manages user session tokens using Jetpack DataStore
 */
class TokenManager(private val context: Context) {

    companion object {
        private val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
        private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
        private val USER_ID_KEY = longPreferencesKey("user_id")
        private val USERNAME_KEY = stringPreferencesKey("username")
        private val THEME_KEY = stringPreferencesKey("theme")
        private val NOTIFICATIONS_ENABLED_KEY = booleanPreferencesKey("notifications_enabled")
        private val SOUND_ENABLED_KEY = booleanPreferencesKey("sound_enabled")
        private val VIBRATION_ENABLED_KEY = booleanPreferencesKey("vibration_enabled")
    }

    suspend fun saveTokens(accessToken: String, refreshToken: String, userId: Long, username: String) {
        context.dataStore.edit { preferences ->
            preferences[ACCESS_TOKEN_KEY] = accessToken
            preferences[REFRESH_TOKEN_KEY] = refreshToken
            preferences[USER_ID_KEY] = userId
            preferences[USERNAME_KEY] = username
        }
    }

    suspend fun saveTheme(theme: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_KEY] = theme
        }
    }

    /**
     * Keeps a local copy of the server-side notification preferences. MessagingFirebaseService
     * decides whether to post a notification while running in the background, with no ViewModel
     * around it and no guarantee that a network call would return in time, so it needs these
     * values available locally and synchronously.
     */
    suspend fun saveNotificationPreferences(
        notificationsEnabled: Boolean,
        soundEnabled: Boolean,
        vibrationEnabled: Boolean
    ) {
        context.dataStore.edit { preferences ->
            preferences[NOTIFICATIONS_ENABLED_KEY] = notificationsEnabled
            preferences[SOUND_ENABLED_KEY] = soundEnabled
            preferences[VIBRATION_ENABLED_KEY] = vibrationEnabled
        }
    }

    val accessToken: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[ACCESS_TOKEN_KEY]
    }

    val refreshToken: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[REFRESH_TOKEN_KEY]
    }

    val userId: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[USER_ID_KEY]
    }

    val username: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[USERNAME_KEY]
    }

    val theme: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[THEME_KEY]
    }

    // Default to true for all three: a fresh install has not synced settings yet, and the server
    // creates every account with notifications on (see AuthServiceImpl), so silence would be wrong.
    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[NOTIFICATIONS_ENABLED_KEY] ?: true
    }

    val soundEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SOUND_ENABLED_KEY] ?: true
    }

    val vibrationEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[VIBRATION_ENABLED_KEY] ?: true
    }

    suspend fun clearTokens() {
        context.dataStore.edit { preferences ->
            preferences.remove(ACCESS_TOKEN_KEY)
            preferences.remove(REFRESH_TOKEN_KEY)
            preferences.remove(USER_ID_KEY)
            preferences.remove(USERNAME_KEY)
            preferences.remove(THEME_KEY)
            preferences.remove(NOTIFICATIONS_ENABLED_KEY)
            preferences.remove(SOUND_ENABLED_KEY)
            preferences.remove(VIBRATION_ENABLED_KEY)
        }
    }
}
