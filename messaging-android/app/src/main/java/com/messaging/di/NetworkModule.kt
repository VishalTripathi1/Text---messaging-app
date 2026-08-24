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

    /**
     * Timeouts sized for a cold backend rather than a warm one.
     *
     * The backend runs on Render's free tier, which stops the instance after roughly 15 minutes of
     * inactivity, and the container is deliberately memory-starved (-Xmx192m, TieredStopAtLevel=1)
     * so it fits in 512 MB - which trades startup time for memory. Every timeout here used to be 15
     * seconds, so the first request after any idle period was guaranteed to fail, which is what made
     * the app look permanently disconnected until the user logged in again.
     *
     * The numbers come from a measured cold start rather than a guess. Render's logs for one:
     * process start to "Started MessagingApplication in 142.9 seconds", then a further 22 seconds
     * before the first response was written. Roughly 180 seconds, against the 90-second read timeout
     * that was here - so a genuine cold start could not succeed, and the backend logged the proof as
     * a Broken pipe while flushing a response the phone had already given up waiting for. 180/240
     * clears the measurement with a little room; anything smaller is arithmetic that cannot work.
     *
     * A ceiling this high costs nothing on a healthy server, where these calls answer in well under
     * a second. It is only ever reached when the alternative is failing.
     *
     * connect stays short because the TCP/TLS handshake terminates at Render's edge and completes
     * immediately whether or not the instance behind it is awake; the waiting is done on the
     * response, which is a read timeout. callTimeout is the backstop so that a request can never
     * hang indefinitely if a socket is left half-open.
     */
    private fun OkHttpClient.Builder.applyColdStartTimeouts(): OkHttpClient.Builder {
        return this
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
    }

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
            .applyColdStartTimeouts()
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
            .applyColdStartTimeouts()
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

        // A WebSocket needs its own client purely for pingInterval, which OkHttp only honours per
        // client. Without it a socket dropped by the mobile network or a NAT timeout stays open as
        // far as the app is concerned - no onFailure, no onClosed - so the reconnect logic never
        // runs and the connection is silently dead until the next send fails. 30s pings surface
        // that within one interval. newBuilder() reuses the connection pool and dispatcher of the
        // main client, so this is not a second connection stack.
        //
        // callTimeout MUST be cleared here. It bounds an entire call, and a WebSocket call is meant
        // to live for as long as the conversation does - inheriting the 120s ceiling from the HTTP
        // client would tear down a perfectly healthy socket every two minutes. Liveness is pings'
        // job, not a deadline's. (OkHttp already zeroes the socket read timeout itself once the
        // handshake upgrades, so readTimeout above does not affect frame reads.)
        val webSocketOkHttpClient = okHttpClient.newBuilder()
            .pingInterval(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        return WebSocketClient(
            okHttpClient = webSocketOkHttpClient,
            tokenManager = tokenManager,
            gson = gson,
            tokenRefreshManager = tokenRefreshManager
        )
    }
}