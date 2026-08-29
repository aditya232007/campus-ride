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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        tracker = DriverLocationTracker(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val cartId = intent?.getStringExtra(EXTRA_CART_ID) ?: "cart_1"
        activeCartId = cartId

        when (action) {
            ACTION_STOP_TRIP -> {
                Log.d(TAG, "Stopping DriverLocationService for cart $activeCartId")
                stopTrackingAndSelf()
                return START_NOT_STICKY
            }
            else -> {
                Log.d(TAG, "Starting DriverLocationService for cart $activeCartId in foreground")
                acquireWakeLock()
                startForegroundWithNotification(activeCartId, "Transmitting live GPS telemetry")
                startLocationTracking(activeCartId)
                return START_STICKY
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
    private fun startForegroundWithNotification(cartId: String, statusText: String) {
        val notification = buildServiceNotification(cartId, statusText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildServiceNotification(cartId: String, statusText: String): Notification {
        val cartLabel = if (cartId == "cart_1") "Cart 1" else "Cart 2"
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
            .setContentTitle("🚐 Campus Ride: $cartLabel Active (On Duty)")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun startLocationTracking(cartId: String) {
        tracker?.startTracking(
            onLocationUpdate = { location ->
                val speedKmH = (location.speed * 3.6f).roundToInt()
                CampusRideRepository.instance?.updateDriverGpsLocation(
                    lat = location.latitude,
                    lng = location.longitude,
                    speedKmH = speedKmH,
                    bearing = location.bearing,
                    accuracy = location.accuracy,
                    cartId = cartId
                )
            },
            onDisabledOrError = {
                CampusRideRepository.instance?.onGpsDisabledOrPermissionMissing()
            }
        )
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
