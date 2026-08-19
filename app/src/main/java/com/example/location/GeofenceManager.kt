package com.example.location

import android.location.Location

private const val TEST_MODE = true

object GeofenceManager {
    // 1. MAIN GATE (Exact Google Maps Pin)
    const val GATE_LAT = 25.2531616
    const val GATE_LNG = 87.0370730
    const val MAX_GEOFENCE_METERS = 70.0

    // 2. TRUNKUT (Exact Google Maps Pin)
    const val TRUNKUT_LAT = 25.2577186
    const val TRUNKUT_LNG = 87.0381730

    // 3. COMPUTER CENTRE (Exact Google Maps Pin)
    const val COMPUTER_CENTRE_LAT = 25.2590500
    const val COMPUTER_CENTRE_LNG = 87.0394730

    // 4. BOYS HOSTEL / HOSTEL (Exact Google Maps Pin)
    const val HOSTEL_LAT = 25.2577810
    const val HOSTEL_LNG = 87.0418910

    // Driver Service Area Center Point (Computer Centre / Academic Hub, IIIT Bhagalpur)
    const val LIBRARY_LAT = 25.2590500
    const val LIBRARY_LNG = 87.0394730
    const val DRIVER_SERVICE_AREA_RADIUS_METERS = 1000.0 // 1 km radius

    val isTestModeEnabled: Boolean get() = TEST_MODE

    /**
     * Calculates precise distance between two Lat/Lng points using standard Android Location API
     */
    fun calculateDistanceMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double = GATE_LAT,
        lng2: Double = GATE_LNG
    ): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0].toDouble()
    }

    /**
     * Calculates precise distance from Vikramshila Library center point (Driver Service Area Center)
     */
    fun calculateDistanceFromLibraryMeters(
        driverLat: Double,
        driverLng: Double
    ): Double {
        val results = FloatArray(1)
        Location.distanceBetween(driverLat, driverLng, LIBRARY_LAT, LIBRARY_LNG, results)
        return results[0].toDouble()
    }

    fun isWithinGeofence(
        studentLat: Double,
        studentLng: Double
    ): Boolean {
        if (TEST_MODE) return true
        val distance = calculateDistanceMeters(studentLat, studentLng)
        return distance <= MAX_GEOFENCE_METERS
    }

    /**
     * Checks if driver's location is within 1000 meters of Vikramshila Library
     */
    fun isWithinDriverServiceArea(
        driverLat: Double,
        driverLng: Double
    ): Boolean {
        val distance = calculateDistanceFromLibraryMeters(driverLat, driverLng)
        return distance <= DRIVER_SERVICE_AREA_RADIUS_METERS
    }
}

