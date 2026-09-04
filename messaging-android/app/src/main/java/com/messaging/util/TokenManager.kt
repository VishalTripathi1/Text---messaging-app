package com.messaging.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.util.UUID

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
        private val DEVICE_ID_KEY = stringPreferencesKey("device_id")
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

    /**
     * A UUID generated once on first call and persisted forever after, so every part of the app
     * agrees on this install's identity.
     *
     * Before this, three different places each computed their own "device id": LoginViewModel
     * generated a brand new random UUID on every single login (so this same physical install
     * could never resume or reuse its previous login's Device/Session row on the backend - every
     * login looked like a new device), while MainActivity and MessagingFirebaseService instead
     * used Settings.Secure.ANDROID_ID. Since login's device id never matched what those other two
     * flows sent, the backend ended up with several unrelated Device rows for what was actually
     * one physical install, and session/refresh-token lookups tied to the wrong one of those
     * rows could fail with "Active session not found for refresh token" even though the install
     * itself never logged out.
     */
    suspend fun getOrCreateDeviceId(): String {
        val existing = context.dataStore.data.map { it[DEVICE_ID_KEY] }.firstOrNull()
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val newId = UUID.randomUUID().toString()
        context.dataStore.edit { preferences ->
            preferences[DEVICE_ID_KEY] = newId
        }
        return newId
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
