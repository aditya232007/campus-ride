package com.example

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp

class CampusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
                Log.d("FCM_BACKGROUND_TEST", "FirebaseApp initialized successfully in CampusApplication")
            } else {
                Log.d("FCM_BACKGROUND_TEST", "FirebaseApp already initialized in CampusApplication")
            }

            try {
                val app = FirebaseApp.getInstance()
                val options = app.options
                Log.d("FCM_BACKGROUND_TEST", "=== FCM DIAGNOSTICS ===")
                Log.d("FCM_BACKGROUND_TEST", "ANDROID APPLICATION ID: $packageName")
                Log.d("FCM_BACKGROUND_TEST", "FCM PROJECT ID: ${options.projectId}")
                Log.d("FCM_BACKGROUND_TEST", "FCM APPLICATION ID: ${options.applicationId}")
                Log.d("FCM_BACKGROUND_TEST", "FCM GCM SENDER ID: ${options.gcmSenderId}")
                Log.d("FCM_BACKGROUND_TEST", "FirebaseApp status: INITIALIZED (${FirebaseApp.getApps(this).size} app(s))")
            } catch (e: Exception) {
                Log.e("FCM_BACKGROUND_TEST", "Error fetching FirebaseApp options", e)
            }

            com.example.notification.CriticalAlertManager.initNotificationChannel(this)
            Log.d("FCM_BACKGROUND_TEST", "CriticalAlertManager notification channels initialized in CampusApplication")
        } catch (e: Exception) {
            Log.e("FCM_BACKGROUND_TEST", "Failed to initialize FirebaseApp or channels in CampusApplication", e)
        }
    }
}

