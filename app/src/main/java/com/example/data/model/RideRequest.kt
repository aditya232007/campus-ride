package com.example.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RequesterType {
    STUDENT,
    FACULTY
}

enum class RideRequestStatus(val label: String) {
    PENDING("Pending Driver"),
    ACCEPTED("Accepted - Driver on the way!"),
    REJECTED("Declined"),
    COMPLETED("Completed")
}

data class RideRequest(
    val id: String = System.currentTimeMillis().toString(),
    val requesterType: RequesterType = RequesterType.STUDENT,
    val studentId: String = "",
    val studentName: String = "",
    val pickupLocation: String = "Main Gate",
    val timestamp: Long = System.currentTimeMillis(),
    val status: RideRequestStatus = RideRequestStatus.PENDING,
    val distanceToGateMeters: Int? = null,
    val studentLat: Double = 25.2428,
    val studentLng: Double = 87.0155,
    val assignedCartId: String? = null,
    val assignedCartName: String? = null
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}

