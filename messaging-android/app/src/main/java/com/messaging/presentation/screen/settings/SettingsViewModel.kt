package com.messaging.presentation.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messaging.data.model.response.UserResponse
import com.messaging.data.model.response.UserSettingsResponse
import com.messaging.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SettingsViewModel - Manages user settings retrieval and persistence
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _profile = MutableStateFlow<UserResponse?>(null)
    val profile: StateFlow<UserResponse?> = _profile

    private val _settings = MutableStateFlow<UserSettingsResponse?>(null)
    val settings: StateFlow<UserSettingsResponse?> = _settings

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            userRepository.getCurrentUser()
                .onSuccess { _profile.value = it }
                .onFailure { _errorMessage.value = it.message }
            userRepository.getSettings()
                .onSuccess { _settings.value = it }
                .onFailure { _errorMessage.value = it.message }
            _isLoading.value = false
        }
    }

    fun updateTheme(theme: String) {
        updateSetting(theme = theme)
    }

    fun updateNotificationsEnabled(enabled: Boolean) {
        updateSetting(notificationsEnabled = enabled)
    }

    fun updateSoundEnabled(enabled: Boolean) {
        updateSetting(soundEnabled = enabled)
    }

    fun updateVibrationEnabled(enabled: Boolean) {
        updateSetting(vibrationEnabled = enabled)
    }

    fun updateShowReadReceipts(enabled: Boolean) {
        updateSetting(showReadReceipts = enabled)
    }

    fun updateShowTypingIndicator(enabled: Boolean) {
        updateSetting(showTypingIndicator = enabled)
    }

    private fun updateSetting(
        theme: String? = null,
        notificationsEnabled: Boolean? = null,
        soundEnabled: Boolean? = null,
        vibrationEnabled: Boolean? = null,
        showReadReceipts: Boolean? = null,
        showTypingIndicator: Boolean? = null
    ) {
        viewModelScope.launch {
            _errorMessage.value = null
            _successMessage.value = null
            userRepository.updateSettings(
                theme = theme,
                notificationsEnabled = notificationsEnabled,
                soundEnabled = soundEnabled,
                vibrationEnabled = vibrationEnabled,
                showReadReceipts = showReadReceipts,
                showTypingIndicator = showTypingIndicator
            )
                .onSuccess {
                    _settings.value = it
                    _successMessage.value = "Setting updated"
                }
                .onFailure { _errorMessage.value = it.message ?: "Update failed" }
        }
    }

    fun clearMessages() {
        _errorMessage.value = null
        _successMessage.value = null
    }
}
