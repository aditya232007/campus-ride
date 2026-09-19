package com.example.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.CampusRideRepository
import kotlin.math.roundToInt

class DriverLocationService : Service() {

    private var tracker: DriverLocationTracker? = null
    private var activeCartId: String = "cart_1"
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationManager: NotificationManager? = null
    private var lastReportedDutyState: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        tracker = DriverLocationTracker(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = CampusRideRepository.getInstance(applicationContext)
        val isManualOffDuty = repo.manualDutyOverride.value

        if (intent == null) {
            if (!isManualOffDuty) {
                val cartId = repo.selectedDriverCartId.value
                activeCartId = cartId
                Log.d(TAG, "DriverLocationService restarted by system with null intent; manual off-duty is NOT set. Resuming background geofencing & tracking.")
                acquireWakeLock()
                val cartLabel = if (activeCartId == "cart_1") "Cart 1" else "Cart 2"
                val started = startForegroundWithNotification(
                    activeCartId,
                    "🚐 Campus Ride: $cartLabel (Monitoring)",
                    "Monitoring IIIT Bhagalpur campus geofence..."
                )
                if (started) {
                    repo.startDriverHeartbeat()
                    startLocationTracking(activeCartId)
                    return START_STICKY
                }
            }
            Log.d(TAG, "DriverLocationService restarted with null intent but driver is manually off duty; stopping safely")
            stopTrackingAndSelf()
            return START_NOT_STICKY
        }

        val action = intent.action
        val cartId = intent.getStringExtra(EXTRA_CART_ID) ?: repo.selectedDriverCartId.value
        activeCartId = cartId

        when (action) {
            ACTION_STOP_TRIP -> {
                Log.d(TAG, "Stopping DriverLocationService for cart $activeCartId")
                stopTrackingAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START_TRIP -> {
                if (isManualOffDuty) {
                    Log.d(TAG, "ACTION_START_TRIP called while driver is manually set to OFF DUTY via Settings; stopping service")
                    stopTrackingAndSelf()
                    return START_NOT_STICKY
                }
                Log.d(TAG, "Starting DriverLocationService for cart $activeCartId in foreground: managing IIIT Bhagalpur campus geofence")
                acquireWakeLock()
                val cartLabel = if (activeCartId == "cart_1") "Cart 1" else "Cart 2"
                val started = startForegroundWithNotification(
                    activeCartId,
                    "🚐 Campus Ride: $cartLabel (Monitoring)",
                    "Monitoring IIIT Bhagalpur campus geofence..."
                )
                if (started) {
                    repo.startDriverHeartbeat()
                    startLocationTracking(activeCartId)
                    return START_STICKY
                } else {
                    Log.w(TAG, "startForegroundWithNotification failed or disallowed; stopping service safely")
                    stopTrackingAndSelf()
                    return START_NOT_STICKY
                }
            }
            else -> {
                Log.d(TAG, "Unknown action $action; stopping DriverLocationService")
                stopTrackingAndSelf()
                return START_NOT_STICKY
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "CampusRide:DriverLocationServiceWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(24 * 60 * 60 * 1000L)
                    Log.d(TAG, "Acquired partial WakeLock for continuous background GPS tracking")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire partial WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "Released partial WakeLock")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock", e)
        }
    }

    @SuppressLint("ForegroundServiceType")
    private fun startForegroundWithNotification(
        cartId: String,
        titleText: String,
        statusText: String
    ): Boolean {
        return try {
            val notification = buildServiceNotification(cartId, titleText, statusText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cannot startForeground in current state: ${e.message}", e)
            false
        }
    }

    private fun buildServiceNotification(
        cartId: String,
        titleText: String,
        statusText: String
    ): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(titleText)
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(cartId: String, titleText: String, statusText: String) {
        try {
            val notification = buildServiceNotification(cartId, titleText, statusText)
            notificationManager?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update foreground notification: ${e.message}")
        }
    }

    private fun startLocationTracking(cartId: String) {
        val repo = CampusRideRepository.getInstance(applicationContext)
        tracker?.startTracking(
            onLocationUpdate = { location ->
                handleLocationAndGeofence(location, cartId, repo)
            },
            onDisabledOrError = {
                repo.onGpsDisabledOrPermissionMissing()
            }
        )
    }

    /**
     * Core IIIT Bhagalpur campus geofencing logic within the background service.
     * Manages duty status continuously:
     * - Checks if user has manually set 'OFF DUTY' via Settings (manual override).
     * - Evaluates physical presence within the IIIT Bhagalpur campus geofence boundary.
     * - Physical location within campus boundary automatically triggers 'ON DUTY' (Available).
     * - Physical location outside campus boundary automatically transitions to 'Outside Campus' (Offline).
     * - Updates repository, telemetry broadcast to students, and foreground notification.
     */
    private fun handleLocationAndGeofence(
        location: Location,
        cartId: String,
        repo: CampusRideRepository
    ) {
        val lat = location.latitude
        val lng = location.longitude
        val accuracy = location.accuracy
        val speedKmH = (location.speed * 3.6f).roundToInt()
        val bearing = location.bearing

        val isManualOffDuty = repo.manualDutyOverride.value
        val isLunchBreak = (repo.driverDutyState.value == "Lunch Break" || repo.lunchBreakRemainingSeconds.value > 0)

        // Evaluate physical presence within IIIT Bhagalpur campus boundary using hysteresis & debounce
        val isInsideCampus = GeofenceManager.evaluateDriverCampusPresence(
            driverLat = lat,
            driverLng = lng,
            accuracy = accuracy,
            cartId = cartId
        )

        val cartLabel = if (cartId == "cart_1") "Cart 1" else "Cart 2"

        val (newDutyState, notificationTitle, notificationText) = when {
            isManualOffDuty -> {
                // Manual OFF DUTY via Settings strictly overrides inside-campus detection
                Triple(
                    "Off Duty",
                    "🚐 Campus Ride: $cartLabel (OFF DUTY)",
                    "Service paused via Settings • Manual override active"
                )
            }
            isLunchBreak -> {
                Triple(
                    "Lunch Break",
                    "🚐 Campus Ride: $cartLabel (Lunch Break)",
                    "Lunch break in progress"
                )
            }
            isInsideCampus -> {
                // Physical location within IIIT Bhagalpur campus boundary triggers automatic 'ON DUTY'
                Triple(
                    "Available",
                    "🚐 Campus Ride: $cartLabel Active (ON DUTY)",
                    "Inside IIIT Bhagalpur Campus • Broadcasting live location"
                )
            }
            else -> {
                // Physical location outside campus boundary -> Driver Not Available
                Triple(
                    "Driver Not Available",
                    "🚐 Campus Ride: $cartLabel (Driver Not Available)",
                    "Outside campus boundary • Driver Not Available"
                )
            }
        }

        // Delegate telemetry and duty sync to repository
        repo.updateDriverGpsLocation(
            lat = lat,
            lng = lng,
            speedKmH = speedKmH,
            bearing = bearing,
            accuracy = accuracy,
            cartId = cartId
        )

        // Update foreground service notification dynamically if state changed
        if (lastReportedDutyState != newDutyState) {
            lastReportedDutyState = newDutyState
            updateNotification(cartId, notificationTitle, notificationText)
        }
    }

    private fun stopTrackingAndSelf() {
        tracker?.stopTracking()
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val repo = CampusRideRepository.getInstance(applicationContext)
        val isManualOff = repo.manualDutyOverride.value
        if (!isManualOff) {
            Log.d(TAG, "onTaskRemoved: App cleared from Recents, but Driver has NOT manually set Off Duty. Preserving foreground geofence service.")
        } else {
            Log.d(TAG, "onTaskRemoved: Driver is manually Off Duty; stopping background service cleanly")
            stopTrackingAndSelf()
        }
    }

    override fun onDestroy() {
        tracker?.stopTracking()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Driver Trip Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service notification for continuous driver GPS tracking"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "DriverLocationService"
        const val CHANNEL_ID = "driver_trip_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START_TRIP = "com.example.action.START_TRIP"
        const val ACTION_STOP_TRIP = "com.example.action.STOP_TRIP"
        const val EXTRA_CART_ID = "extra_cart_id"

        fun startTrip(context: Context, cartId: String) {
            val intent = Intent(context, DriverLocationService::class.java).apply {
                action = ACTION_START_TRIP
                putExtra(EXTRA_CART_ID, cartId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start DriverLocationService", e)
            }
        }

        fun stopTrip(context: Context) {
            val intent = Intent(context, DriverLocationService::class.java).apply {
                action = ACTION_STOP_TRIP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop DriverLocationService", e)
            }
        }
    }
}
