package com.example.notification

import android.util.Log
import com.example.data.model.RequesterType
import com.example.data.model.RideRequest
import com.example.data.model.RideRequestStatus
import com.example.data.model.UserRole
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class CampusFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Token received: $token")
        
        // Persist/Sync FCM token to Firestore under drivers/cart_1
        try {
            val firestore = FirebaseFirestore.getInstance()
            val driverData = mapOf(
                "cartId" to "cart_1",
                "fcmToken" to token,
                "driverStatus" to "Available",
                "isAvailable" to true,
                "lastUpdatedMillis" to System.currentTimeMillis()
            )
            firestore.collection("drivers")
                .document("cart_1")
                .set(driverData, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "Driver FCM token successfully synced to Firestore drivers/cart_1")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to sync FCM token to Firestore", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Firestore in onNewToken", e)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM Message Received from: ${remoteMessage.from}")

        val data = remoteMessage.data
        if (data.isNotEmpty()) {
            val type = data["type"] ?: "RIDE_REQUEST"
            if (type == "RIDE_REQUEST") {
                val reqId = data["requestId"] ?: "req_${System.currentTimeMillis()}"
                val requesterTypeStr = data["requesterType"] ?: "STUDENT"
                val requesterType = if (requesterTypeStr == "FACULTY") RequesterType.FACULTY else RequesterType.STUDENT
                val pickupLoc = data["pickupLocation"] ?: "Main Gate"
                val studentName = data["studentName"] ?: ""
                val distMeters = data["distanceToGateMeters"]?.toIntOrNull() ?: 0
                val cartId = data["assignedCartId"] ?: "cart_1"
                val cartName = data["assignedCartName"] ?: "Golf Cart 1"

                val rideRequest = RideRequest(
                    id = reqId,
                    requesterType = requesterType,
                    studentName = studentName,
                    pickupLocation = pickupLoc,
                    distanceToGateMeters = distMeters,
                    status = RideRequestStatus.PENDING,
                    timestamp = System.currentTimeMillis(),
                    assignedCartId = cartId,
                    assignedCartName = cartName
                )

                Log.d(TAG, "Triggering Critical Driver Alert from FCM background service for request: ${rideRequest.id}")
                
                // Trigger full-screen alert UI, looping alarm sound, and continuous vibration
                CriticalAlertManager.triggerCriticalDriverAlert(
                    context = applicationContext,
                    request = rideRequest,
                    currentRole = UserRole.DRIVER
                )
            }
        }
    }

    companion object {
        private const val TAG = "CampusFcmService"
    }
}
