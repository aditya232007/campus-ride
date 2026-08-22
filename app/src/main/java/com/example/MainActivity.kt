package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.example.ui.navigation.CampusAppNavigation
import com.example.ui.theme.CampusRideTheme

class MainActivity : ComponentActivity() {
  private val currentIntentState = mutableStateOf<Intent?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    currentIntentState.value = intent

    enableEdgeToEdge()
    com.example.notification.CriticalAlertManager.initNotificationChannel(applicationContext)
    setContent {
      CampusRideTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          CampusAppNavigation(intent = currentIntentState.value)
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    currentIntentState.value = intent
  }
}

