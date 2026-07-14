package com.messaging.domain.usecase

import com.messaging.data.model.request.RegisterRequest
import com.messaging.data.model.response.AuthResponse
import com.messaging.domain.repository.AuthRepository
import javax.inject.Inject

/**
 * RegisterUseCase - Business logic for user registration
 */
class RegisterUseCase @Inject constructor(
    private val repository: AuthRepository
) {
    suspend operator fun invoke(request: RegisterRequest): Result<AuthResponse> {
        return repository.register(request)
    }
}
