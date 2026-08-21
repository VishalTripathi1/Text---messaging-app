package com.messaging.di

import android.content.Context
import com.google.gson.Gson
import com.messaging.data.datasource.remote.AuthApiService
import com.messaging.data.datasource.remote.AuthInterceptor
import com.messaging.data.datasource.remote.ChatApiService
import com.messaging.data.datasource.remote.TokenAuthenticator
import com.messaging.data.datasource.remote.TokenRefreshManager
import com.messaging.data.datasource.remote.WebSocketClient
import com.messaging.util.Constants
import com.messaging.util.TokenManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the internal OkHttpClient used only for token-refresh calls.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlainOkHttpClient

/**
 * NetworkModule
 *
 * Configures:
 *  - Retrofit
 *  - OkHttp
 *  - authentication interceptor
 *  - token authenticator
 *  - token refresh manager
 *  - WebSocket client
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val BASE_URL =
        Constants.BASE_URL

    @Singleton
    @Provides
    fun provideTokenManager(
        @ApplicationContext context: Context
    ): TokenManager {
        return TokenManager(context)
    }

    @Singleton
    @Provides
    fun provideGson(): Gson {
        return Gson()
    }

    @Singleton
    @Provides
    fun provideAuthInterceptor(
        tokenManager: TokenManager
    ): AuthInterceptor {
        return AuthInterceptor(tokenManager)
    }

    @Singleton
    @Provides
    fun provideLoggingInterceptor():
            HttpLoggingInterceptor {

        return HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
    }

    /**
     * Bare OkHttp client.
     *
     * This client intentionally does NOT contain:
     *  - AuthInterceptor
     *  - TokenAuthenticator
     *
     * Otherwise the refresh-token request could recursively
     * trigger another token refresh.
     */
    @Singleton
    @Provides
    @PlainOkHttpClient
    fun providePlainOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient {

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(
                15,
                TimeUnit.SECONDS
            )
            .readTimeout(
                15,
                TimeUnit.SECONDS
            )
            .writeTimeout(
                15,
                TimeUnit.SECONDS
            )
            .build()
    }

    /**
     * Shared token refresh manager.
     */
    @Singleton
    @Provides
    fun provideTokenRefreshManager(
        tokenManager: TokenManager,
        @PlainOkHttpClient plainOkHttpClient: OkHttpClient,
        gson: Gson
    ): TokenRefreshManager {

        return TokenRefreshManager(
            tokenManager = tokenManager,
            plainOkHttpClient = plainOkHttpClient,
            gson = gson
        )
    }

    /**
     * Handles expired JWT access tokens for normal HTTP requests.
     */
    @Singleton
    @Provides
    fun provideTokenAuthenticator(
        tokenRefreshManager: TokenRefreshManager
    ): TokenAuthenticator {

        return TokenAuthenticator(
            tokenRefreshManager
        )
    }

    /**
     * Main authenticated OkHttp client.
     */
    @Singleton
    @Provides
    fun provideOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor,
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator
    ): OkHttpClient {

        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .authenticator(tokenAuthenticator)
            .connectTimeout(
                15,
                TimeUnit.SECONDS
            )
            .readTimeout(
                15,
                TimeUnit.SECONDS
            )
            .writeTimeout(
                15,
                TimeUnit.SECONDS
            )
            .build()
    }

    @Singleton
    @Provides
    fun provideRetrofit(
        okHttpClient: OkHttpClient
    ): Retrofit {

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(
                GsonConverterFactory.create()
            )
            .build()
    }

    @Singleton
    @Provides
    fun provideAuthApiService(
        retrofit: Retrofit
    ): AuthApiService {

        return retrofit.create(
            AuthApiService::class.java
        )
    }

    @Singleton
    @Provides
    fun provideChatApiService(
        retrofit: Retrofit
    ): ChatApiService {

        return retrofit.create(
            ChatApiService::class.java
        )
    }

    @Singleton
    @Provides
    fun provideWebSocketClient(
        okHttpClient: OkHttpClient,
        tokenManager: TokenManager,
        gson: Gson,
        tokenRefreshManager: TokenRefreshManager
    ): WebSocketClient {

        return WebSocketClient(
            okHttpClient = okHttpClient,
            tokenManager = tokenManager,
            gson = gson,
            tokenRefreshManager = tokenRefreshManager
        )
    }
}