package com.example.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
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
 * Robust continuous high-accuracy live location provider client for Campus Ride Drivers.
 *
 * Uses Google Play Services FusedLocationProviderClient with continuous 2-second updates
 * and 0.5m displacement filter, with automatic fallback to Android LocationManager.
 */
class DriverLocationTracker(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var fusedLocationCallback: LocationCallback? = null
    private var legacyLocationListener: LocationListener? = null
    private var isTracking = false

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
            Log.w("DriverLocationTracker", "Location permissions not granted, cannot start tracking")
            onDisabledOrError()
            return
        }

        if (isTracking) {
            stopTracking()
        }

        isTracking = true
        Log.d("DriverLocationTracker", "Starting continuous high-accuracy driver live location tracking...")

        // 1. Check last known location immediately for instant initialization
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                if (lastLoc != null) {
                    logGpsUpdate(lastLoc, "LAST_KNOWN_FUSED")
                    onLocationUpdate(lastLoc)
                }
            }.addOnFailureListener {
                Log.w("DriverLocationTracker", "Failed to obtain last known fused location", it)
            }
        } catch (e: Exception) {
            Log.w("DriverLocationTracker", "Exception getting last location", e)
        }

        // 2. Configure High-Accuracy Continuous LocationRequest (2s interval, 1s min interval, 0.5m distance)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(0.5f)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    logGpsUpdate(location, "FUSED_LIVE")
                    onLocationUpdate(location)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    Log.w("DriverLocationTracker", "Fused location reporting temporary unavailability")
                }
            }
        }
        fusedLocationCallback = callback

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                callback,
                Looper.getMainLooper()
            ).addOnFailureListener { e ->
                Log.e("DriverLocationTracker", "FusedLocationProviderClient failed, activating LocationManager fallback", e)
                startLegacyFallback(onLocationUpdate, onDisabledOrError)
            }
        } catch (e: Exception) {
            Log.e("DriverLocationTracker", "Exception requesting fused location updates", e)
            startLegacyFallback(onLocationUpdate, onDisabledOrError)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLegacyFallback(
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
                logGpsUpdate(location, "LEGACY_LIVE")
                onLocationUpdate(location)
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
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L,
                    0.5f,
                    listener,
                    Looper.getMainLooper()
                )
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    0.5f,
                    listener,
                    Looper.getMainLooper()
                )
            }
        } catch (e: Exception) {
            Log.e("DriverLocationTracker", "Failed to start legacy LocationManager updates", e)
            onDisabledOrError()
        }
    }

    fun stopTracking() {
        isTracking = false
        fusedLocationCallback?.let {
            try {
                fusedLocationClient.removeLocationUpdates(it)
            } catch (e: Exception) {
                Log.w("DriverLocationTracker", "Error removing fused updates", e)
            }
            fusedLocationCallback = null
        }
        legacyLocationListener?.let {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                lm?.removeUpdates(it)
            } catch (e: Exception) {
                Log.w("DriverLocationTracker", "Error removing legacy updates", e)
            }
            legacyLocationListener = null
        }
        Log.d("DriverLocationTracker", "Driver location tracking stopped.")
    }

    private fun logGpsUpdate(location: Location, source: String) {
        Log.d(
            "DRIVER_GPS",
            """
            DRIVER LOCATION UPDATE ($source)
            Latitude: ${location.latitude}
            Longitude: ${location.longitude}
            Accuracy: ${location.accuracy}
            Speed: ${location.speed}
            Bearing: ${location.bearing}
            Timestamp: ${location.time}
            """.trimIndent()
        )
    }
}
