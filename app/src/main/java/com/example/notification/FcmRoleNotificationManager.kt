package com.example.notification

import android.content.Context
import android.util.Log
import com.example.data.api.CampusBackendClient
import com.example.data.api.FcmTokenSyncRequest
import com.example.data.model.RideRequest
import com.example.data.model.UserRole
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object FcmRoleNotificationManager {
    private const val TAG = "FcmRoleManager"

    fun maskToken(token: String?): String {
        if (token.isNullOrBlank()) return "NULL_OR_EMPTY"
        if (token.length <= 12) return "MASKED(${token.length} chars)"
        return "${token.take(6)}...${token.takeLast(6)}"
    }

    fun syncRoleFcmSubscription(context: Context, role: UserRole) {
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(context)
        val isGpsAvailable = resultCode == ConnectionResult.SUCCESS
        val gpsStatusMsg = googleApiAvailability.getErrorString(resultCode)

        Log.d("FCM_BACKGROUND_TEST", "=== FCM TOKEN REGISTRATION DIAGNOSTICS ===")
        Log.d("FCM_BACKGROUND_TEST", "ANDROID APPLICATION ID: ${context.packageName}")
        try {
            val app = com.google.firebase.FirebaseApp.getInstance()
            val options = app.options
            Log.d("FCM_BACKGROUND_TEST", "FCM PROJECT ID: ${options.projectId}")
            Log.d("FCM_BACKGROUND_TEST", "FCM APPLICATION ID: ${options.applicationId}")
            Log.d("FCM_BACKGROUND_TEST", "FCM GCM SENDER ID: ${options.gcmSenderId}")
            Log.d("FCM_BACKGROUND_TEST", "FirebaseApp initialization status: INITIALIZED")
        } catch (e: Exception) {
            Log.e("FCM_BACKGROUND_TEST", "FirebaseApp initialization status: FAILED (${e.message})")
        }
        Log.d("FCM_BACKGROUND_TEST", "Google Play Services availability: $isGpsAvailable (code $resultCode: $gpsStatusMsg)")

        if (!isGpsAvailable) {
            Log.w(TAG, "Google Play Services unavailable (code $resultCode: $gpsStatusMsg). FCM registration skipped.")
            return
        }

        val topic = when (role) {
            UserRole.STUDENT -> "students"
            UserRole.FACULTY -> "faculty"
            UserRole.DRIVER -> "drivers"
        }

        try {
            try {
                val userTopic = when (role) {
                    UserRole.STUDENT -> "students"
                    UserRole.FACULTY -> "faculty"
                    UserRole.DRIVER -> "drivers"
                }
                FirebaseMessaging.getInstance().subscribeToTopic(userTopic)
                    .addOnSuccessListener {
                        Log.d(TAG, "Subscribed to FCM topic successfully: $userTopic")
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "FCM topic subscription for $userTopic skipped/failed: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Topic management skipped: ${e.message}")
            }

            Log.d("FCM_BACKGROUND_TEST", "Requesting FirebaseMessaging.getInstance().token for $role...")
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (!token.isNullOrEmpty()) {
                        val masked = maskToken(token)
                        Log.d("FCM_BACKGROUND_TEST", "FirebaseMessaging.getToken() SUCCESS for $role: $masked")
                        saveAndSyncToken(context, token, role)
                    } else {
                        Log.w("FCM_BACKGROUND_TEST", "FirebaseMessaging.getToken() SUCCESS for $role but returned NULL/EMPTY.")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("FCM_BACKGROUND_TEST", "FirebaseMessaging.getToken() FAILURE for $role!")
                    Log.e("FCM_BACKGROUND_TEST", "Exception Class: ${e.javaClass.name}")
                    Log.e("FCM_BACKGROUND_TEST", "Exception Message: ${e.message}")
                    if (e is com.google.android.gms.common.api.ApiException) {
                        Log.e("FCM_BACKGROUND_TEST", "ApiException Status Code: ${e.statusCode}")
                    }
                }
        } catch (e: Throwable) {
            Log.e("FCM_BACKGROUND_TEST", "FCM Messaging service error during subscription or token fetch: ${e.message}", e)
        }
    }

    fun saveAndSyncToken(context: Context, token: String, role: UserRole) {
        if (token.isBlank() || token.startsWith("fallback_")) {
            Log.w("FCM_BACKGROUND_TEST", "Skipping token save/sync for empty or fake token: ${maskToken(token)}")
            return
        }
        val masked = maskToken(token)
        val roleStr = role.name.uppercase()

        if (role == UserRole.DRIVER) {
            Log.d("FCM_BACKGROUND_TEST", "Uploading real Driver FCM Token to Firestore drivers/cart_1 and Render: $masked")
            try {
                val firestore = FirebaseFirestore.getInstance()
                val driverDoc = mapOf(
                    "cartId" to "cart_1",
                    "fcmToken" to token,
                    "driverStatus" to "Available",
                    "isAvailable" to true,
                    "lastUpdatedMillis" to System.currentTimeMillis()
                )
                firestore.collection("drivers")
                    .document("cart_1")
                    .set(driverDoc, com.google.firebase.firestore.SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d("FCM_BACKGROUND_TEST", "Driver FCM Token successfully written to Firestore drivers/cart_1: $masked")
                    }
                    .addOnFailureListener { e ->
                        Log.e("FCM_BACKGROUND_TEST", "Failed to write Driver FCM token to Firestore", e)
                    }

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        Log.d("FCM_BACKGROUND_TEST", "Sending POST to Render /api/notifications/fcm-token with DRIVER token: $masked")
                        CampusBackendClient.api.syncFcmToken(
                            FcmTokenSyncRequest(
                                role = "DRIVER",
                                userId = "cart_1",
                                fcmToken = token
                            )
                        )
                        Log.d("FCM_BACKGROUND_TEST", "Driver FCM token successfully uploaded to Render backend: $masked")
                    } catch (e: Exception) {
                        Log.e("FCM_BACKGROUND_TEST", "Failed uploading Driver FCM token to Render backend", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("FCM_BACKGROUND_TEST", "Exception in saveAndSyncToken for DRIVER", e)
            }
        } else {
            // STUDENT or FACULTY token - strictly isolated from DRIVER token
            Log.d("FCM_BACKGROUND_TEST", "Uploading $roleStr FCM Token to Render backend (preserving DRIVER token): $masked")
            try {
                val firestore = FirebaseFirestore.getInstance()
                val docId = "${role.name.lowercase()}_device"
                val userDoc = mapOf(
                    "role" to roleStr,
                    "fcmToken" to token,
                    "lastUpdatedMillis" to System.currentTimeMillis()
                )
                firestore.collection("fcm_tokens")
                    .document(docId)
                    .set(userDoc, com.google.firebase.firestore.SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d("FCM_BACKGROUND_TEST", "$roleStr FCM Token successfully written to Firestore fcm_tokens/$docId: $masked")
                    }
                    .addOnFailureListener { e ->
                        Log.e("FCM_BACKGROUND_TEST", "Failed to write $roleStr FCM token to Firestore", e)
                    }

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        Log.d("FCM_BACKGROUND_TEST", "Sending POST to Render /api/notifications/fcm-token with $roleStr token: $masked")
                        CampusBackendClient.api.syncFcmToken(
                            FcmTokenSyncRequest(
                                role = roleStr,
                                userId = "student_device",
                                fcmToken = token
                            )
                        )
                        Log.d("FCM_BACKGROUND_TEST", "$roleStr FCM token successfully uploaded to Render backend: $masked")
                    } catch (e: Exception) {
                        Log.e("FCM_BACKGROUND_TEST", "Failed uploading $roleStr FCM token to Render backend", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("FCM_BACKGROUND_TEST", "Exception in saveAndSyncToken for $roleStr", e)
            }
        }
    }

    fun dispatchDriverPush(request: RideRequest) {
        Log.d(TAG, "Routing real FCM push dispatch for request ID: ${request.id}")
        try {
            val firestore = FirebaseFirestore.getInstance()
            val notificationPayload = mapOf(
                "id" to "notif_${request.id}",
                "requestId" to request.id,
                "requesterType" to request.requesterType.name,
                "pickupLocation" to request.pickupLocation,
                "studentName" to request.studentName,
                "distanceToGateMeters" to request.distanceToGateMeters,
                "assignedCartId" to (request.assignedCartId ?: "cart_1"),
                "type" to "RIDE_REQUEST",
                "targetTopic" to "drivers",
                "status" to "QUEUED",
                "timestamp" to System.currentTimeMillis()
            )
            
            firestore.collection("fcm_dispatches")
                .document("dispatch_${request.id}")
                .set(notificationPayload)
                .addOnSuccessListener {
                    Log.d(TAG, "FCM Push dispatch document written to Firestore for request ${request.id}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to write FCM dispatch to Firestore", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching FCM push to driver", e)
        }
    }

    fun dispatchStudentAcceptancePush(request: RideRequest) {
        Log.d(TAG, "Routing acceptance push to student for request ID: ${request.id}")
        try {
            val firestore = FirebaseFirestore.getInstance()
            val acceptancePayload = mapOf(
                "id" to "accept_${request.id}",
                "requestId" to request.id,
                "type" to "REQUEST_ACCEPTED",
                "targetTopic" to "students",
                "timestamp" to System.currentTimeMillis()
            )
            firestore.collection("fcm_dispatches")
                .document("accept_${request.id}")
                .set(acceptancePayload)
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching student acceptance push", e)
        }
    }
}
