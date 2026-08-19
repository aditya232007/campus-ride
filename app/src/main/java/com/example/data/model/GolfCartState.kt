package com.example.data.model

enum class GolfCartStatus(val label: String) {
    MOVING("Moving"),
    HALTED("Halted"),
    OFFLINE("Offline")
}

data class GolfCartState(
    val cartId: String? = null,
    val cartName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedKmH: Int? = null,
    val bearing: Float? = null,
    val status: GolfCartStatus? = null,
    val batteryLevel: Int? = null,
    val lastUpdatedMillis: Long? = null,
    val distanceToGateMeters: Int? = null,
    val distanceToUserMeters: Int? = null,
    val relativeMovement: String? = null, // "Coming Towards You", "Moving Away", "Stationary"
    val etaMinutes: Int? = null,
    val driverStatus: String? = null,
    val isAvailable: Boolean = false,
    val activeRequestId: String? = null,
    val accuracy: Float? = null
) {
    val landmarkZone: String
        get() = com.example.location.CampusLandmarkZone.getCartLocationDescription(latitude, longitude)
}


