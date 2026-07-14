package com.messaging.domain.usecase

import com.messaging.data.model.request.LoginRequest
import com.messaging.data.model.response.AuthResponse
import com.messaging.domain.repository.AuthRepository
import javax.inject.Inject

/**
 * LoginUseCase - Business logic for user login
 */
class LoginUseCase @Inject constructor(
    private val repository: AuthRepository
) {
    suspend operator fun invoke(request: LoginRequest): Result<AuthResponse> {
        return repository.login(request)
    }
}
