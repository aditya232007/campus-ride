package com.example.location

import android.location.Location

// =========================================================================================
// TEMPORARY TESTING ONLY:
// TODO: REMOVE BEFORE PRODUCTION RELEASE:
// Temporary any-location ride request testing bypass.
// Set TEST_MODE_ALLOW_ANY_PICKUP_LOCATION = false before final public release.
// When false: Production 70.0-meter Main Gate geofence rule is strictly enforced.
// =========================================================================================
const val TEST_MODE_ALLOW_ANY_PICKUP_LOCATION = true

object GeofenceManager {
    // 1. MAIN GATE (Exact Google Maps Pin - Production Coordinates)
    const val GATE_LAT = 25.2531616
    const val GATE_LNG = 87.0370730
    const val MAX_GEOFENCE_METERS = 70.0 // Production Geofence Radius: 70 meters

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

    // Campus Geofence Boundary for Automatic Driver Entry Detection
    const val CAMPUS_CENTER_LAT = 25.2590500
    const val CAMPUS_CENTER_LNG = 87.0394730
    const val CAMPUS_GEOFENCE_RADIUS_METERS = 1000.0 // 1 km radius for full campus zone
    const val MAX_ACCEPTABLE_GPS_ACCURACY_METERS = 50.0 // Reject inaccurate fixes (>50m) to avoid false detections

    val isTestModeEnabled: Boolean get() = TEST_MODE_ALLOW_ANY_PICKUP_LOCATION

    /**
     * Validates whether GPS fix accuracy is sufficient for reliable geofence detection.
     */
    fun isGpsAccuracyValid(accuracy: Float): Boolean {
        // REMOVE BEFORE PRODUCTION RELEASE: Temporary testing bypass
        if (TEST_MODE_ALLOW_ANY_PICKUP_LOCATION) return true
        return accuracy > 0f && accuracy <= MAX_ACCEPTABLE_GPS_ACCURACY_METERS
    }

    /**
     * Checks if a driver's GPS coordinate is within the campus geofence with valid accuracy.
     */
    fun isInsideCampusGeofence(
        driverLat: Double,
        driverLng: Double,
        accuracy: Float = 0f
    ): Boolean {
        // REMOVE BEFORE PRODUCTION RELEASE: Temporary testing bypass
        if (TEST_MODE_ALLOW_ANY_PICKUP_LOCATION) return true
        if (accuracy > 0f && !isGpsAccuracyValid(accuracy)) {
            return false // Reject inaccurate GPS spikes
        }
        val distance = calculateDistanceMeters(driverLat, driverLng, CAMPUS_CENTER_LAT, CAMPUS_CENTER_LNG)
        return distance <= CAMPUS_GEOFENCE_RADIUS_METERS
    }

    /**
     * Calculates precise distance between two Lat/Lng points using mathematical Haversine formula
     */
    fun calculateDistanceMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double = GATE_LAT,
        lng2: Double = GATE_LNG
    ): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }

    /**
     * Calculates precise distance from Vikramshila Library center point (Driver Service Area Center)
     */
    fun calculateDistanceFromLibraryMeters(
        driverLat: Double,
        driverLng: Double
    ): Double {
        return calculateDistanceMeters(driverLat, driverLng, LIBRARY_LAT, LIBRARY_LNG)
    }

    /**
     * Validates whether a student is within the 70.0-meter Main Gate geofence radius.
     * When TEST_MODE_ALLOW_ANY_PICKUP_LOCATION is false (production), strictly enforces the 70-meter boundary.
     */
    fun isWithinGeofence(
        studentLat: Double,
        studentLng: Double
    ): Boolean {
        // REMOVE BEFORE PRODUCTION RELEASE: Temporary any-location ride request testing bypass
        if (TEST_MODE_ALLOW_ANY_PICKUP_LOCATION) {
            return true
        } else {
            // EXISTING 70-meter geofence rule PRESERVED INTACT
            val distance = calculateDistanceMeters(studentLat, studentLng, GATE_LAT, GATE_LNG)
            return distance <= MAX_GEOFENCE_METERS
        }
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

