package com.messaging

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * MessagingApp - Application entry point
 * 
 * Initializes:
 * - Hilt dependency injection
 * - Timber logging
 * - Firebase Cloud Messaging
 */
@HiltAndroidApp
class MessagingApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        Timber.d("MessagingApp initialized")
    }
}
