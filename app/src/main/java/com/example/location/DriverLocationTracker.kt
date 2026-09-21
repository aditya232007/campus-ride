package com.example.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Production-grade battery-optimized live location tracker for Campus Ride Drivers.
 *
 * Implements an adaptive movement state machine:
 * - MOVING: High accuracy, 3-second interval, 3.5m displacement threshold.
 * - STATIONARY: Balanced power, 12-second interval, 8m displacement threshold.
 *
 * Automatically detects when the golf cart stops or resumes moving, and filters
 * out small GPS jitter noise so the device's CPU and radio are not kept at peak
 * power consumption unnecessarily while parked or at rest.
 */
class DriverLocationTracker(private val context: Context) {

    enum class MovementState {
        MOVING,
        STATIONARY
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var fusedLocationCallback: LocationCallback? = null
    private var legacyLocationListener: LocationListener? = null
    private var backgroundThread: HandlerThread? = null
    private var isTracking = false

    // State machine & filtering variables
    private var currentMovementState = MovementState.MOVING
    private var stationaryAnchorLocation: Location? = null
    private var consecutiveStationarySamples = 0
    private var lastEmittedLocation: Location? = null
    private var lastEmittedTimeMs = 0L

    fun isLocationPermissionGranted(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    @SuppressLint("MissingPermission")
    fun startTracking(
        onLocationUpdate: (Location) -> Unit,
        onDisabledOrError: () -> Unit
    ) {
        if (!isLocationPermissionGranted()) {
            Log.w(TAG, "Location permissions not granted, cannot start tracking")
            onDisabledOrError()
            return
        }

        if (isTracking) {
            stopTracking()
        }

        isTracking = true
        currentMovementState = MovementState.MOVING
        consecutiveStationarySamples = 0
        stationaryAnchorLocation = null
        lastEmittedLocation = null
        lastEmittedTimeMs = 0L

        Log.d(TAG, "Starting adaptive battery-efficient driver live location tracking...")

        // Create dedicated HandlerThread for background location callbacks
        val thread = HandlerThread("DriverLocationTrackerThread", android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE).apply {
            start()
        }
        backgroundThread = thread
        val backgroundLooper = thread.looper ?: Looper.getMainLooper()

        // 1. Initial immediate location check for fast UI readiness
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                if (lastLoc != null) {
                    logGpsUpdate(lastLoc, "LAST_KNOWN_FUSED")
                    stationaryAnchorLocation = lastLoc
                    lastEmittedLocation = lastLoc
                    lastEmittedTimeMs = System.currentTimeMillis()
                    onLocationUpdate(lastLoc)
                }
            }.addOnFailureListener {
                Log.w(TAG, "Failed to obtain last known fused location", it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception getting last location", e)
        }

        // 2. Setup callback with adaptive stationary detection and drift filtering
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (rawLoc in result.locations) {
                    processIncomingLocation(rawLoc, backgroundLooper, onLocationUpdate)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    Log.w(TAG, "Fused location reporting temporary unavailability")
                }
            }
        }
        fusedLocationCallback = callback

        // 3. Register initial request (MOVING mode)
        val initialRequest = buildLocationRequest(MovementState.MOVING)
        try {
            fusedLocationClient.requestLocationUpdates(
                initialRequest,
                callback,
                backgroundLooper
            ).addOnFailureListener { e ->
                Log.e(TAG, "FusedLocationProviderClient failed, activating LocationManager fallback", e)
                startLegacyFallback(backgroundLooper, onLocationUpdate, onDisabledOrError)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception requesting fused location updates", e)
            startLegacyFallback(backgroundLooper, onLocationUpdate, onDisabledOrError)
        }
    }

    /**
     * Filters GPS jitter, updates moving/stationary state, and emits updates to listener.
     */
    private fun processIncomingLocation(
        location: Location,
        backgroundLooper: Looper,
        onLocationUpdate: (Location) -> Unit
    ) {
        val now = System.currentTimeMillis()

        // Discard severely inaccurate GPS readings (> 40m accuracy) if we already have a fix
        if (location.accuracy > ACCURACY_FILTER_THRESHOLD_M && lastEmittedLocation != null) {
            Log.d(TAG, "Discarding poor accuracy GPS point (${location.accuracy}m > ${ACCURACY_FILTER_THRESHOLD_M}m)")
            return
        }

        if (stationaryAnchorLocation == null) {
            stationaryAnchorLocation = location
        }

        val anchor = stationaryAnchorLocation!!
        val distFromAnchor = location.distanceTo(anchor)
        val speedMps = if (location.hasSpeed()) location.speed else 0f

        // Stationary vs Moving State Transition Evaluation
        if (currentMovementState == MovementState.MOVING) {
            if (speedMps < STATIONARY_SPEED_THRESHOLD_MPS && distFromAnchor < STATIONARY_DISPLACEMENT_THRESHOLD_M) {
                consecutiveStationarySamples++
                if (consecutiveStationarySamples >= SAMPLES_TO_STATIONARY) {
                    // Transition to STATIONARY
                    currentMovementState = MovementState.STATIONARY
                    stationaryAnchorLocation = location
                    consecutiveStationarySamples = 0
                    Log.d(TAG, "STATIONARY DETECTION: Cart is parked or stationary. Switching to balanced-power mode (12s interval, 8m filter).")
                    reconfigureFusedUpdates(MovementState.STATIONARY, backgroundLooper)
                }
            } else {
                consecutiveStationarySamples = 0
                if (distFromAnchor >= STATIONARY_DISPLACEMENT_THRESHOLD_M) {
                    stationaryAnchorLocation = location
                }
            }
        } else {
            // Currently STATIONARY
            if (speedMps >= MOVING_SPEED_THRESHOLD_MPS || distFromAnchor >= STATIONARY_DISPLACEMENT_THRESHOLD_M) {
                // Transition back to MOVING
                currentMovementState = MovementState.MOVING
                stationaryAnchorLocation = location
                consecutiveStationarySamples = 0
                Log.d(TAG, "MOVEMENT DETECTION: Cart resumed moving (speed=${speedMps}m/s, distFromAnchor=${distFromAnchor}m). Switching to high-accuracy tracking (3s interval, 3.5m filter).")
                reconfigureFusedUpdates(MovementState.MOVING, backgroundLooper)
            }
        }

        // GPS Jitter & Displacement Gate
        val last = lastEmittedLocation
        if (last == null) {
            lastEmittedLocation = location
            lastEmittedTimeMs = now
            logGpsUpdate(location, "INITIAL_FIX")
            onLocationUpdate(location)
            return
        }

        val distFromLast = location.distanceTo(last)
        val timeSinceLast = now - lastEmittedTimeMs

        // Emit if:
        // 1. Moved more than 2.0m, OR
        // 2. We are in MOVING state and moved at least 1.5m, OR
        // 3. Keep-alive timeout passed (15 seconds) so driver status/heartbeat stays fresh
        val shouldEmit = distFromLast >= MIN_EMIT_DISTANCE_M ||
                (currentMovementState == MovementState.MOVING && distFromLast >= 1.5f) ||
                timeSinceLast >= MAX_STATIONARY_HEARTBEAT_EMIT_MS

        if (shouldEmit) {
            lastEmittedLocation = location
            lastEmittedTimeMs = now
            logGpsUpdate(location, if (currentMovementState == MovementState.MOVING) "FUSED_MOVING" else "FUSED_STATIONARY")
            onLocationUpdate(location)
        } else {
            Log.v(TAG, "Suppressed stationary GPS jitter: dist=${distFromLast}m, dt=${timeSinceLast}ms")
        }
    }

    @SuppressLint("MissingPermission")
    private fun reconfigureFusedUpdates(state: MovementState, backgroundLooper: Looper) {
        val callback = fusedLocationCallback ?: return
        try {
            val newRequest = buildLocationRequest(state)
            fusedLocationClient.removeLocationUpdates(callback).addOnCompleteListener {
                try {
                    fusedLocationClient.requestLocationUpdates(newRequest, callback, backgroundLooper)
                } catch (e: Exception) {
                    Log.w(TAG, "Error applying reconfigured location request: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during fused update reconfiguration: ${e.message}")
        }
    }

    private fun buildLocationRequest(state: MovementState): LocationRequest {
        return when (state) {
            MovementState.MOVING -> {
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, MOVING_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(MOVING_MIN_INTERVAL_MS)
                    .setMinUpdateDistanceMeters(MOVING_MIN_DISTANCE_M)
                    .setMaxUpdateDelayMillis(MOVING_INTERVAL_MS)
                    .setWaitForAccurateLocation(false)
                    .build()
            }
            MovementState.STATIONARY -> {
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, STATIONARY_INTERVAL_MS)
                    .setMinUpdateIntervalMillis(STATIONARY_MIN_INTERVAL_MS)
                    .setMinUpdateDistanceMeters(STATIONARY_MIN_DISTANCE_M)
                    .setMaxUpdateDelayMillis(STATIONARY_MAX_DELAY_MS)
                    .setWaitForAccurateLocation(false)
                    .build()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLegacyFallback(
        backgroundLooper: Looper,
        onLocationUpdate: (Location) -> Unit,
        onDisabledOrError: () -> Unit
    ) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            onDisabledOrError()
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                processIncomingLocation(location, backgroundLooper, onLocationUpdate)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                    !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                ) {
                    onDisabledOrError()
                }
            }
        }
        legacyLocationListener = listener

        try {
            // Prioritize GPS_PROVIDER if enabled, otherwise fallback to NETWORK_PROVIDER
            // Avoid registering both simultaneously to prevent double hardware waking
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MOVING_INTERVAL_MS,
                    MOVING_MIN_DISTANCE_M,
                    listener,
                    backgroundLooper
                )
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    STATIONARY_INTERVAL_MS,
                    STATIONARY_MIN_DISTANCE_M,
                    listener,
                    backgroundLooper
                )
            } else {
                onDisabledOrError()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start legacy LocationManager updates", e)
            onDisabledOrError()
        }
    }

    fun stopTracking() {
        isTracking = false
        fusedLocationCallback?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing fused updates", e)
            }
            fusedLocationCallback = null
        }
        legacyLocationListener?.let {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                lm?.removeUpdates(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing legacy updates", e)
            }
            legacyLocationListener = null
        }
        backgroundThread?.let {
            try {
                it.quitSafely()
            } catch (e: Exception) {
                Log.w(TAG, "Error quitting background thread", e)
            }
            backgroundThread = null
        }
        stationaryAnchorLocation = null
        lastEmittedLocation = null
        Log.d(TAG, "Driver location tracking stopped.")
    }

    private fun logGpsUpdate(location: Location, source: String) {
        Log.d(
            "DRIVER_GPS",
            "LOCATION_TICK [$source]: Lat=${location.latitude}, Lng=${location.longitude}, Acc=${location.accuracy}m, Spd=${location.speed}m/s, State=$currentMovementState"
        )
    }

    companion object {
        private const val TAG = "DriverLocationTracker"

        // Moving configuration: 3s interval, 3.5m min distance
        private const val MOVING_INTERVAL_MS = 3000L
        private const val MOVING_MIN_INTERVAL_MS = 2000L
        private const val MOVING_MIN_DISTANCE_M = 3.5f

        // Stationary configuration: 12s interval, 8m min distance, balanced power
        private const val STATIONARY_INTERVAL_MS = 12000L
        private const val STATIONARY_MIN_INTERVAL_MS = 8000L
        private const val STATIONARY_MIN_DISTANCE_M = 8.0f
        private const val STATIONARY_MAX_DELAY_MS = 15000L

        // Detection thresholds
        private const val STATIONARY_SPEED_THRESHOLD_MPS = 0.8f // ~2.9 km/h
        private const val MOVING_SPEED_THRESHOLD_MPS = 1.2f     // ~4.3 km/h
        private const val STATIONARY_DISPLACEMENT_THRESHOLD_M = 7.0f
        private const val SAMPLES_TO_STATIONARY = 4 // ~12-15 seconds of remaining in place

        // Filtering
        private const val ACCURACY_FILTER_THRESHOLD_M = 40.0f
        private const val MIN_EMIT_DISTANCE_M = 2.0f
        private const val MAX_STATIONARY_HEARTBEAT_EMIT_MS = 15000L
    }
}
