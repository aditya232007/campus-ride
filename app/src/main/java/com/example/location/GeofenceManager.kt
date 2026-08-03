package com.example.location

import android.location.Location

private const val TEST_MODE = true

object GeofenceManager {
    // IIIT Bhagalpur Main Gate Coordinates (Exact Center Point)
    const val GATE_LAT = 25.2531616
    const val GATE_LNG = 87.0370730
    const val MAX_GEOFENCE_METERS = 70.0

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

    fun isWithinGeofence(
        studentLat: Double,
        studentLng: Double
    ): Boolean {
        if (TEST_MODE) return true
        val distance = calculateDistanceMeters(studentLat, studentLng)
        return distance <= MAX_GEOFENCE_METERS
    }
}

