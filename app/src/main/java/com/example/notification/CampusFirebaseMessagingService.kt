package com.example.notification

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.data.api.CampusBackendClient
import com.example.data.api.FcmTokenSyncRequest
import com.example.data.model.RequesterType
import com.example.data.model.RideRequest
import com.example.data.model.RideRequestStatus
import com.example.data.model.UserRole
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CampusFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val masked = FcmRoleNotificationManager.maskToken(token)
        Log.d("FCM_BACKGROUND_TEST", "onNewToken() triggered on device. New real FCM token: $masked")

        if (token.isBlank() || token.startsWith("fallback_")) {
            Log.w("FCM_BACKGROUND_TEST", "onNewToken received invalid or fallback token, skipping sync.")
            return
        }

        val prefs = getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE)
        val savedRoleStr = prefs.getString("saved_user_role", null)
        val savedRole = UserRole.fromString(savedRoleStr) ?: UserRole.STUDENT

        Log.d("FCM_BACKGROUND_TEST", "onNewToken handling token sync for device role: ${savedRole.name}")
        FcmRoleNotificationManager.saveAndSyncToken(applicationContext, token, savedRole)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        val isForeground = isAppInForeground()
        Log.d("FCM_BACKGROUND_TEST", "=== FCM MESSAGE RECEIVED ===")
        Log.d("FCM_BACKGROUND_TEST", "App State: ${if (isForeground) "FOREGROUND" else "BACKGROUND / KILLED"}")
        Log.d("FCM_BACKGROUND_TEST", "From: ${remoteMessage.from}, MessageID: ${remoteMessage.messageId}")
        Log.d("FCM_BACKGROUND_TEST", "Data Payload: ${remoteMessage.data}")
        Log.d("FCM_BACKGROUND_TEST", "Notification Payload present: ${remoteMessage.notification != null}")

        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            val type = data["type"] ?: "RIDE_REQUEST"
            if (type == "RIDE_REQUEST" || type == "NEW_RIDE_REQUEST") {
                // FIRST STEP: Check saved user role from SharedPreferences before performing ANY notification/alert action
                val prefs = getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE)
                val savedRoleStr = prefs.getString("saved_user_role", null)
                val activeRole = UserRole.fromString(savedRoleStr) ?: UserRole.STUDENT

                if (activeRole != UserRole.DRIVER) {
                    Log.d("FCM_BACKGROUND_TEST", "Ignoring $type FCM message on non-DRIVER device (Active Role: ${activeRole.name}, Saved String: $savedRoleStr)")
                    return
                }

                val reqId = data["requestId"] ?: data["rideId"] ?: "req_${System.currentTimeMillis()}"
                val requesterTypeStr = data["requesterType"] ?: "STUDENT"
                val requesterType = if (requesterTypeStr == "FACULTY") RequesterType.FACULTY else RequesterType.STUDENT
                val pickupLoc = data["pickupLocation"] ?: "Main Gate"
                
                val passengerName = data["passengerName"]?.takeIf { it.isNotBlank() }
                    ?: data["studentName"]?.takeIf { it.isNotBlank() }
                    ?: if (requesterType == RequesterType.FACULTY) "Faculty Member" else "Passenger"

                val distMeters = data["distanceToGateMeters"]?.toIntOrNull() ?: 0
                val cartId = data["assignedCartId"] ?: "cart_1"
                val cartName = data["assignedCartName"] ?: "Golf Cart 1"

                try {
                    val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                    @Suppress("DEPRECATION")
                    val wakeLock = powerManager?.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        android.os.PowerManager.ON_AFTER_RELEASE,
                        "CampusRide:FcmServiceWakeLock"
                    )
                    wakeLock?.acquire(10000L)
                } catch (e: Exception) {
                    Log.w("CampusFcmService", "WakeLock warning: ${e.message}")
                }

                val studentsWaitingCount = data["studentsWaiting"]?.toIntOrNull() ?: 1

                val rideRequest = RideRequest(
                    id = reqId,
                    requesterType = requesterType,
                    studentName = passengerName,
                    pickupLocation = pickupLoc,
                    distanceToGateMeters = distMeters,
                    studentsWaiting = studentsWaitingCount,
                    status = RideRequestStatus.PENDING,
                    timestamp = System.currentTimeMillis(),
                    assignedCartId = cartId,
                    assignedCartName = cartName
                )

                // Check notification permission
                val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
                Log.d("FCM_BACKGROUND_TEST", "POST_NOTIFICATIONS Permission Granted: $hasNotificationPermission")

                // Check notification channel importance
                CriticalAlertManager.initNotificationChannel(applicationContext)
                val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    notificationManager.getNotificationChannel(CriticalAlertManager.CHANNEL_ID)
                } else null
                
                val importanceText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    when (channel?.importance) {
                        NotificationManager.IMPORTANCE_HIGH -> "IMPORTANCE_HIGH (5)"
                        NotificationManager.IMPORTANCE_DEFAULT -> "IMPORTANCE_DEFAULT (3)"
                        NotificationManager.IMPORTANCE_LOW -> "IMPORTANCE_LOW (2)"
                        NotificationManager.IMPORTANCE_MIN -> "IMPORTANCE_MIN (1)"
                        NotificationManager.IMPORTANCE_NONE -> "IMPORTANCE_NONE (0 - DISABLED BY USER)"
                        else -> "UNKNOWN (${channel?.importance})"
                    }
                } else "PRE-OREO"
                Log.d("FCM_BACKGROUND_TEST", "Notification Channel Importance: $importanceText")

                val intent = Intent(applicationContext, com.example.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("requestId", reqId)
                    putExtra("rideId", reqId)
                    putExtra("type", type)
                    putExtra("requesterType", requesterTypeStr)
                    putExtra("pickupLocation", pickupLoc)
                    putExtra("passengerName", passengerName)
                }
                val pendingIntent = PendingIntent.getActivity(
                    applicationContext,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val pickupLocationEnum = rideRequest.pickupLocationEnum
                val defaultTitle = if (requesterType == RequesterType.FACULTY) {
                    "🚨 FACULTY • ${pickupLocationEnum.shortLabel}"
                } else {
                    "🚨 ${pickupLocationEnum.shortLabel}"
                }
                val waitingCountLabel = if (studentsWaitingCount == 1) "1 STUDENT WAITING" else "$studentsWaitingCount STUDENTS WAITING"
                val defaultBody = "$waitingCountLabel\nCampus Ride request"
                val notificationTitle = data["title"]?.takeIf { it.isNotBlank() } ?: defaultTitle
                val notificationBody = data["body"]?.takeIf { it.isNotBlank() } ?: defaultBody

                val notification = NotificationCompat.Builder(applicationContext, CriticalAlertManager.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(notificationTitle)
                    .setContentText(notificationBody)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setFullScreenIntent(pendingIntent, true)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setSound(null)
                    .setVibrate(longArrayOf(0))
                    .build()

                Log.d("FCM_BACKGROUND_TEST", "Calling NotificationManager.notify(id=${CriticalAlertManager.NOTIFICATION_ID})")
                notificationManager.notify(CriticalAlertManager.NOTIFICATION_ID, notification)
                Log.d("FCM_BACKGROUND_TEST", "NotificationManager.notify() called successfully")

                CriticalAlertManager.triggerCriticalDriverAlert(
                    context = applicationContext,
                    request = rideRequest,
                    currentRole = activeRole
                )
            }
        }
    }

    private fun isAppInForeground(): Boolean {
        return try {
            val appProcessInfo = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(appProcessInfo)
            appProcessInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "CampusFcmService"
    }
}
