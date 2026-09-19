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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object FcmRoleNotificationManager {
    private const val TAG = "FcmRoleManager"

    fun maskToken(token: String?): String {
        if (token.isNullOrBlank()) return "NULL_OR_EMPTY"
        if (token.length <= 12) return "MASKED(${token.length} chars)"
        return "${token.take(6)}...${token.takeLast(6)}"
    }

    fun isRealFcmToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        if (token.startsWith("fallback_") || token.startsWith("device_") || token.length < 20) return false
        return true
    }

    /**
     * Updates topic subscriptions based on active role and cart.
     * Ensures driver is subscribed to BOTH general 'drivers' topic and cart-specific 'driver_$cartId' topic.
     */
    fun updateTopicSubscriptions(context: Context, role: UserRole, cartId: String = "cart_1") {
        val messaging = try { FirebaseMessaging.getInstance() } catch (_: Exception) { return }

        if (role == UserRole.DRIVER) {
            // Subscribe to drivers & specific cart topic
            messaging.subscribeToTopic("drivers")
                .addOnSuccessListener {
                    Log.d("CAMPUS_RIDE_AUDIT", "DRIVER_TOPIC_SUBSCRIBED: Successfully subscribed to 'drivers'")
                }
                .addOnFailureListener { e ->
                    Log.w("CAMPUS_RIDE_AUDIT", "DRIVER_TOPIC_NOTICE: Failed to subscribe to 'drivers': ${e.message}")
                }

            val targetCartTopic = "driver_$cartId"
            messaging.subscribeToTopic(targetCartTopic)
                .addOnSuccessListener {
                    Log.d("CAMPUS_RIDE_AUDIT", "DRIVER_CART_TOPIC_SUBSCRIBED: Successfully subscribed to '$targetCartTopic'")
                }
                .addOnFailureListener { e ->
                    Log.w("CAMPUS_RIDE_AUDIT", "DRIVER_CART_TOPIC_NOTICE: Failed to subscribe to '$targetCartTopic': ${e.message}")
                }

            // Unsubscribe from other cart topics
            val otherCartId = if (cartId == "cart_1") "cart_2" else "cart_1"
            messaging.unsubscribeFromTopic("driver_$otherCartId")
            messaging.unsubscribeFromTopic("students")
        } else {
            // Student or Faculty
            messaging.subscribeToTopic("students")
            messaging.unsubscribeFromTopic("drivers")
            messaging.unsubscribeFromTopic("driver_cart_1")
            messaging.unsubscribeFromTopic("driver_cart_2")
        }
    }

    fun syncRoleFcmSubscription(context: Context, role: UserRole) {
        val prefs = context.getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE)
        val targetCartId = prefs.getString("selected_driver_cart_id", "cart_1") ?: "cart_1"

        // 1. Immediately configure topic subscriptions
        updateTopicSubscriptions(context, role, targetCartId)

        // 2. Check Google Play Services availability
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(context)
        val isGpsAvailable = resultCode == ConnectionResult.SUCCESS
        val gpsStatusMsg = googleApiAvailability.getErrorString(resultCode)

        Log.d(TAG, "Syncing FCM subscription for role: $role, Google Play Services available: $isGpsAvailable ($gpsStatusMsg)")

        // 3. If a valid FCM token is ALREADY cached in prefs, sync it immediately
        val cachedToken = prefs.getString("fcm_token", null)
            ?: prefs.getString("driver_fcm_token", null)

        if (isRealFcmToken(cachedToken)) {
            Log.d("CAMPUS_RIDE_AUDIT", "FCM_CACHED_TOKEN_FOUND: Found valid cached token, syncing immediately for $role")
            saveAndSyncToken(context, cachedToken!!, role)
        }

        if (!isGpsAvailable) {
            Log.w(TAG, "Google Play Services unavailable (code $resultCode: $gpsStatusMsg). Waiting for service availability.")
            return
        }

        // 4. Asynchronously query FirebaseMessaging token with active retry & hard-failure recovery
        CoroutineScope(Dispatchers.IO).launch {
            var tokenRetrieved = false
            var attempts = 0
            val maxAttempts = 4

            while (!tokenRetrieved && attempts < maxAttempts) {
                attempts++
                try {
                    Log.d("CAMPUS_RIDE_AUDIT", "FCM_TOKEN_FETCH_ATTEMPT_$attempts: Querying FirebaseMessaging token for $role")
                    val token = FirebaseMessaging.getInstance().token.await()

                    if (isRealFcmToken(token)) {
                        tokenRetrieved = true
                        Log.d("CAMPUS_RIDE_AUDIT", "4. DRIVER_FCM_TOKEN_FOUND: Retrieved valid FCM token for $role: ${maskToken(token)}")
                        prefs.edit()
                            .putString("fcm_token", token)
                            .apply()
                        if (role == UserRole.DRIVER) {
                            prefs.edit().putString("driver_fcm_token", token).apply()
                        }
                        saveAndSyncToken(context, token, role)
                    } else {
                        Log.w(TAG, "Empty or invalid token returned from FCM SDK (attempt $attempts)")
                    }
                } catch (e: Exception) {
                    val errText = e.message ?: "Unknown error"
                    Log.w("CAMPUS_RIDE_AUDIT", "FCM_TOKEN_FETCH_NOTICE: Attempt $attempts failed ($errText)")

                    // If hard failure (FCM Registration failed), reset token so GMS can re-register cleanly
                    if (errText.contains("FCM Registration failed", ignoreCase = true) ||
                        errText.contains("hard failure", ignoreCase = true) ||
                        errText.contains("SERVICE_NOT_AVAILABLE", ignoreCase = true)) {
                        try {
                            Log.d("CAMPUS_RIDE_AUDIT", "FCM_HARD_FAILURE_RECOVERY: Resetting faulted GMS FCM registration token...")
                            FirebaseMessaging.getInstance().deleteToken().await()
                        } catch (delErr: Exception) {
                            Log.w(TAG, "Notice during deleteToken recovery: ${delErr.message}")
                        }
                    }

                    if (attempts < maxAttempts) {
                        val backoffMs = 2000L * attempts
                        Log.d(TAG, "Retrying FCM token fetch in ${backoffMs}ms...")
                        delay(backoffMs)
                    }
                }
            }
        }
    }

    fun saveAndSyncToken(context: Context, token: String, role: UserRole) {
        if (!isRealFcmToken(token)) {
            Log.w("CAMPUS_RIDE_AUDIT", "saveAndSyncToken: Rejected non-FCM token: $token")
            return
        }

        // Always cache in SharedPreferences so Permission checks and UI can immediately detect it
        try {
            val prefs = context.getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("fcm_token", token).apply()
            if (role == UserRole.DRIVER) {
                prefs.edit().putString("driver_fcm_token", token).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error caching fcm_token in SharedPreferences: ${e.message}")
        }

        val roleStr = role.name.uppercase()

        CoroutineScope(Dispatchers.IO).launch {
            // Ensure auth before writing to Firestore
            try {
                val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                if (auth.currentUser == null) {
                    auth.signInAnonymously().await()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Early auth notice before token sync: ${e.message}")
            }

            if (role == UserRole.DRIVER) {
                val prefs = context.getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE)
                val targetCartId = prefs.getString("selected_driver_cart_id", "cart_1") ?: "cart_1"
                transmitDriverTokenImmediately(context, token, targetCartId)
            } else {
                // STUDENT or FACULTY token - strictly isolated from DRIVER token
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
                        .addOnFailureListener { e ->
                            Log.w(TAG, "$roleStr FCM token Firestore sync notice: ${e.message}")
                        }

                    try {
                        CampusBackendClient.api.syncFcmToken(
                            FcmTokenSyncRequest(
                                role = roleStr,
                                userId = docId,
                                fcmToken = token
                            )
                        )
                    } catch (timeout: java.net.SocketTimeoutException) {
                        Log.w(TAG, "Render backend cold start timeout during $roleStr token sync: ${timeout.message}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Notice uploading $roleStr FCM token to Render backend: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Exception in saveAndSyncToken for $roleStr: ${e.message}")
                }
            }
        }
    }

    /**
     * Immediately and resiliently transmits the refreshed Driver FCM token to the backend and Firestore.
     * Retries with exponential backoff if the network or backend encounters a transient issue.
     */
    fun transmitDriverTokenImmediately(
        context: Context,
        token: String,
        cartId: String = "cart_1"
    ) {
        if (!isRealFcmToken(token)) {
            Log.w("CAMPUS_RIDE_AUDIT", "DRIVER_TOKEN_REFRESH: Skipping invalid/fallback token")
            return
        }

        val masked = maskToken(token)
        Log.d("CAMPUS_RIDE_AUDIT", "DRIVER_TOKEN_REFRESH_START: Immediate push token transmission for cart=$cartId, token=$masked")

        val prefs = context.getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("fcm_token", token)
            .putString("driver_fcm_token", token)
            .putString("selected_driver_cart_id", cartId)
            .putLong("last_driver_token_refresh_time", System.currentTimeMillis())
            .putBoolean("driver_fcm_token_synced", false)
            .apply()

        // Ensure topic subscriptions to both 'drivers' and 'driver_$cartId'
        updateTopicSubscriptions(context, UserRole.DRIVER, cartId)

        // Asynchronously launch immediate transmission with retry loop
        CoroutineScope(Dispatchers.IO).launch {
            val maxAttempts = 3
            var attempt = 0
            var success = false

            while (attempt < maxAttempts && !success) {
                attempt++
                try {
                    Log.d("CAMPUS_RIDE_AUDIT", "DRIVER_TOKEN_SYNC_ATTEMPT_$attempt: Sending POST /api/notifications/fcm-token for cart=$cartId")
                    val response = CampusBackendClient.api.syncFcmToken(
                        FcmTokenSyncRequest(
                            role = "DRIVER",
                            userId = "driver_$cartId",
                            cartId = cartId,
                            fcmToken = token
                        )
                    )

                    if (response.isSuccessful && response.body()?.success == true) {
                        success = true
                        prefs.edit()
                            .putBoolean("driver_fcm_token_synced", true)
                            .putLong("driver_fcm_token_synced_at", System.currentTimeMillis())
                            .apply()
                        Log.d("CAMPUS_RIDE_AUDIT", "DRIVER_FCM_TOKEN_REFRESH_SUCCESS: Backend confirmed driver token for $cartId on attempt $attempt")
                    } else {
                        Log.w("CAMPUS_RIDE_AUDIT", "DRIVER_TOKEN_SYNC_HTTP_NOTICE: Code ${response.code()}, message: ${response.message()}")
                    }
                } catch (e: Exception) {
                    Log.w("CAMPUS_RIDE_AUDIT", "DRIVER_TOKEN_SYNC_EXCEPTION: Attempt $attempt failed (${e.message})")
                }

                if (!success && attempt < maxAttempts) {
                    delay(1000L * attempt)
                }
            }

            // Sync to Firestore non-blockingly
            try {
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("drivers")
                    .document(cartId)
                    .set(
                        mapOf(
                            "cartId" to cartId,
                            "fcmToken" to token,
                            "isOnline" to true,
                            "isAvailable" to true,
                            "lastUpdatedMillis" to System.currentTimeMillis()
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    )
                    .addOnSuccessListener {
                        Log.d("CAMPUS_RIDE_AUDIT", "DRIVER_TOKEN_FIRESTORE_SYNCED: drivers/$cartId updated with new token")
                    }
                    .addOnFailureListener { e ->
                        Log.w("CAMPUS_RIDE_AUDIT", "DRIVER_TOKEN_FIRESTORE_NOTICE: ${e.message}")
                    }

                firestore.collection("fcm_tokens")
                    .document("driver_$cartId")
                    .set(
                        mapOf(
                            "role" to "DRIVER",
                            "cartId" to cartId,
                            "userId" to "driver_$cartId",
                            "fcmToken" to token,
                            "updatedAt" to System.currentTimeMillis()
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    )
            } catch (e: Exception) {
                Log.w("CAMPUS_RIDE_AUDIT", "Firestore driver token background sync notice: ${e.message}")
            }
        }
    }

    fun dispatchDriverPush(request: RideRequest) {
        Log.d(TAG, "Routing real FCM push dispatch for request ID: ${request.id}")
        try {
            val firestore = FirebaseFirestore.getInstance()
            val targetCart = request.assignedCartId ?: "cart_1"
            val notificationPayload = mapOf(
                "id" to "notif_${request.id}",
                "requestId" to request.id,
                "requesterType" to request.requesterType.name,
                "pickupLocation" to request.pickupLocation,
                "studentName" to request.studentName,
                "distanceToGateMeters" to request.distanceToGateMeters,
                "assignedCartId" to targetCart,
                "type" to "RIDE_REQUEST",
                "targetTopic" to "driver_$targetCart",
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
