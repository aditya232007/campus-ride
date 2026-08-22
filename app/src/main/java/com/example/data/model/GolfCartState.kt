package com.example.data.model

enum class GolfCartStatus(val label: String) {
    MOVING("Moving"),
    HALTED("Halted"),
    OFFLINE("Offline")
}

data class GolfCartState(
    val cartId: String? = null,
    val cartName: String? = null,
    val driverId: String? = null,
    val tripId: String? = null,
    val isTripActive: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedKmH: Int? = null,
    val bearing: Float? = null,
    val accuracy: Float? = null,
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
    val direction: String? = null, // e.g. "Trunkut → Main Gate", "Computer Centre → Hostel"
    val currentStop: String? = null, // e.g. "Trunkut", "Main Gate", "Near Computer Centre"
    val nextStop: String? = null // e.g. "Main Gate", "Computer Centre", "Hostel"
) {
    val landmarkZone: String
        get() = com.example.location.CampusLandmarkZone.getCartLocationDescription(latitude, longitude)

    /**
     * Determines whether the cart is actively broadcasting fresh GPS coordinates.
     */
    val isLive: Boolean
        get() {
            if (status == GolfCartStatus.OFFLINE) return false
            if (latitude == null || longitude == null) return false
            val ageMs = lastUpdatedMillis?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE
            return ageMs < 60_000
        }

    /**
     * Formats the last updated timestamp into human readable relative string.
     */
    val lastUpdatedFormatted: String
        get() {
            val updateTime = lastUpdatedMillis ?: return "No GPS signal"
            val diffSec = (System.currentTimeMillis() - updateTime) / 1000
            return when {
                diffSec < 10 -> "Updated just now"
                diffSec < 60 -> "Updated ${diffSec}s ago"
                diffSec < 3600 -> "Last updated ${diffSec / 60}m ago"
                else -> "Last updated >1h ago"
            }
        }

    val displayCartLabel: String
        get() = when (cartId) {
            "cart_1" -> "Cart 1"
            "cart_2" -> "Cart 2"
            else -> cartName ?: "Campus Cart"
        }

    val displayCurrentLocation: String
        get() = currentStop ?: landmarkZone

    val displayDirection: String
        get() = direction ?: when {
            landmarkZone.contains("Hostel", ignoreCase = true) -> "Hostel → Main Gate"
            landmarkZone.contains("Gate", ignoreCase = true) -> "Main Gate → Hostel"
            else -> "In Transit"
        }

    val displayNextStop: String
        get() = nextStop ?: when {
            landmarkZone.contains("Hostel", ignoreCase = true) -> "Computer Centre"
            landmarkZone.contains("Computer", ignoreCase = true) -> "Trunkut"
            landmarkZone.contains("Trunkut", ignoreCase = true) -> "Main Gate"
            else -> "Next Stop"
        }
}


