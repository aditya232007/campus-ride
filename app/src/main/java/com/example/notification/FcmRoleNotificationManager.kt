package com.example.notification

import android.content.Context
import android.util.Log
import com.example.data.model.RideRequest
import com.example.data.model.UserRole
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging

object FcmRoleNotificationManager {
    private const val TAG = "FcmRoleManager"

    fun syncRoleFcmSubscription(context: Context, role: UserRole) {
        val topic = when (role) {
            UserRole.STUDENT -> "students"
            UserRole.FACULTY -> "faculty"
            UserRole.DRIVER -> "drivers"
        }

        try {
            FirebaseMessaging.getInstance().subscribeToTopic(topic)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d(TAG, "Subscribed to FCM topic successfully: $topic")
                    } else {
                        Log.e(TAG, "Failed to subscribe to FCM topic: $topic", task.exception)
                    }
                }

            if (role == UserRole.DRIVER) {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (task.isSuccessful && task.result != null) {
                        val token = task.result
                        Log.d(TAG, "Driver FCM Token retrieved: $token")
                        
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
                                Log.d(TAG, "Driver token successfully saved to Firestore drivers/cart_1")
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Failed to save Driver token to Firestore", e)
                            }
                    } else {
                        Log.e(TAG, "Failed to retrieve FCM Token for driver", task.exception)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in syncRoleFcmSubscription", e)
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
