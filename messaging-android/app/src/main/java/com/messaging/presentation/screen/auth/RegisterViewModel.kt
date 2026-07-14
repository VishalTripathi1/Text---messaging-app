package com.messaging.presentation.screen.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messaging.data.model.request.RegisterRequest
import com.messaging.domain.usecase.RegisterUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * RegisterViewModel - Coordinates registration screen states and flows
 */
@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val registerUseCase: RegisterUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun register(
        username: String,
        email: String,
        displayName: String,
        password: String,
        phoneNumber: String? = null
    ) {
        if (username.isBlank() || email.isBlank() || displayName.isBlank() || password.isBlank()) {
            _uiState.value = AuthUiState.Error("All fields except phone number are required")
            return
        }

        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            val request = RegisterRequest(
                username = username,
                email = email,
                password = password,
                displayName = displayName,
                phoneNumber = phoneNumber
            )

            registerUseCase(request).fold(
                onSuccess = {
                    _uiState.value = AuthUiState.Success
                },
                onFailure = { error ->
                    _uiState.value = AuthUiState.Error(error.message ?: "Registration failed")
                }
            )
        }
    }

    fun resetState() {
        _uiState.value = AuthUiState.Idle
    }
}
