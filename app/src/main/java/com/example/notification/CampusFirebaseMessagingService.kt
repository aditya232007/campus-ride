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

                val reqId = data["requestId"] ?: data["rideId"] ?: data["id"] ?: data["request_id"] ?: data["ride_id"] ?: "req_dispatch"
                Log.d("CAMPUS_RIDE_TRACE", "FCM_RECEIVED: requestId=$reqId, type=$type, role=${activeRole.name}")

                if (CriticalAlertManager.isRequestHandled(reqId)) {
                    Log.d("CAMPUS_RIDE_TRACE", "FCM_IGNORED: Request $reqId is already handled.")
                    return
                }

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
                    val wakeLock = powerManager?.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK,
                        "CampusRide:FcmServiceWakeLock"
                    )
                    wakeLock?.acquire(10000L)
                } catch (e: Exception) {
                    Log.w("CampusFcmService", "WakeLock notice: ${e.message}")
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

                CriticalAlertManager.initNotificationChannel(applicationContext)
                Log.d("FCM_BACKGROUND_TEST", "Dispatching alert to CriticalAlertManager for request $reqId")

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
