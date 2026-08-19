package com.example.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.model.RideRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CriticalAlertManager {
    const val CHANNEL_ID = "driver_critical_alerts"
    const val CHANNEL_NAME = "Critical Ride Requests"
    const val NOTIFICATION_ID = 8801

    private var activeMediaPlayer: android.media.MediaPlayer? = null
    private var activeVibrator: Vibrator? = null
    private var previewMediaPlayer: android.media.MediaPlayer? = null

    private val _activeAlertRequest = MutableStateFlow<RideRequest?>(null)
    val activeAlertRequest: StateFlow<RideRequest?> = _activeAlertRequest.asStateFlow()

    private var lastAlertedRequestId: String? = null
    private var lastAlertTimestamp: Long = 0L

    private fun getRawResourceId(context: Context): Int {
        return context.resources.getIdentifier("campus_ride_alert", "raw", context.packageName)
    }

    fun createMediaPlayerForUri(context: Context, uriStr: String): android.media.MediaPlayer? {
        val appContext = context.applicationContext
        return try {
            if (uriStr == "bundled" || uriStr.isBlank()) {
                val rawId = getRawResourceId(context)
                if (rawId != 0) {
                    android.media.MediaPlayer.create(appContext, rawId)
                } else {
                    val defaultAlarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    android.media.MediaPlayer.create(appContext, defaultAlarm)
                }
            } else {
                val uri = android.net.Uri.parse(uriStr)
                android.media.MediaPlayer.create(appContext, uri) ?: run {
                    val rawId = getRawResourceId(context)
                    if (rawId != 0) android.media.MediaPlayer.create(appContext, rawId) else null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            val defaultAlarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            try {
                android.media.MediaPlayer.create(appContext, defaultAlarm)
            } catch (e2: Exception) {
                null
            }
        }
    }

    fun playRingtonePreview(context: Context, uriStr: String) {
        stopRingtonePreview()
        try {
            previewMediaPlayer = createMediaPlayerForUri(context, uriStr)?.apply {
                isLooping = false
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopRingtonePreview() {
        try {
            previewMediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
            previewMediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private const val STUDENT_CHANNEL_ID = "student_ride_updates"
    private const val STUDENT_CHANNEL_NAME = "Ride Status Updates"
    private const val STUDENT_NOTIFICATION_ID = 8802

    fun initNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // If channel was previously created with a sound attached, delete it so it gets recreated silent
            val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existingChannel != null && existingChannel.sound != null) {
                try {
                    notificationManager.deleteNotificationChannel(CHANNEL_ID)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val driverChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority full-screen alerts for golf cart drivers"
                enableVibration(false)
                vibrationPattern = longArrayOf(0)
                setSound(null, null) // System notification sound disabled; sound is managed exclusively by CriticalAlertManager.activeMediaPlayer
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }

            val studentChannel = NotificationChannel(
                STUDENT_CHANNEL_ID,
                STUDENT_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Informational status updates for students"
                enableVibration(false)
                setSound(null, null)
            }

            notificationManager.createNotificationChannel(driverChannel)
            notificationManager.createNotificationChannel(studentChannel)
        }
    }

    fun triggerCriticalDriverAlert(context: Context, request: RideRequest, currentRole: com.example.data.model.UserRole?) {
        _activeAlertRequest.value = request

        // CRITICAL ROUTING RULE:
        // High priority vibration, audio loop, and full screen alert MUST ONLY trigger on DRIVER devices.
        if (currentRole != com.example.data.model.UserRole.DRIVER) {
            return
        }

        val now = System.currentTimeMillis()
        if (request.id == lastAlertedRequestId && (now - lastAlertTimestamp) < 10000L && activeMediaPlayer?.isPlaying == true) {
            android.util.Log.d("CriticalAlertManager", "Ignoring duplicate alert trigger for request ${request.id}")
            return
        }
        lastAlertedRequestId = request.id
        lastAlertTimestamp = now

        // 0. Acquire temporary WakeLock to wake screen and CPU on locked/screen-off devices (Pixel, Samsung, Xiaomi, OnePlus, OPPO, vivo, Motorola, Nokia)
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager?.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                android.os.PowerManager.ON_AFTER_RELEASE,
                "CampusRide:DriverCriticalAlertWakeLock"
            )
            wakeLock?.acquire(8000L)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Load driver sound & vibration preferences
        val prefs = context.getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE)
        val ringtoneUriStr = prefs.getString("driver_ringtone_uri", "bundled") ?: "bundled"
        val vibrationEnabled = prefs.getBoolean("driver_vibration_enabled", true)
        val repeatingEnabled = prefs.getBoolean("driver_repeating_alert_enabled", true)

        // 1. Play configured alert sound for driver
        try {
            stopSoundAndVibration()

            activeMediaPlayer = createMediaPlayerForUri(context, ringtoneUriStr)?.apply {
                isLooping = repeatingEnabled
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Start continuous vibration for driver if enabled
        if (vibrationEnabled) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager =
                        context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vibratorManager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                activeVibrator = vibrator

                val pattern = longArrayOf(0, 600, 400, 600, 400)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, if (repeatingEnabled) 0 else -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, if (repeatingEnabled) 0 else -1)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. Post system heads-up notification on driver device
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = if (request.requesterType == com.example.data.model.RequesterType.FACULTY) {
                "🚨 FACULTY • ${request.pickupLocationEnum.shortLabel}"
            } else {
                "🚨 ${request.pickupLocationEnum.shortLabel}"
            }
            val waitingStr = if (request.studentsWaiting == 1) "1 STUDENT WAITING" else "${request.studentsWaiting} STUDENTS WAITING"
            val text = "$waitingStr • Campus Ride request"

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pendingIntent, true)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setSound(null)
                .setVibrate(longArrayOf(0))
                .build()

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun triggerStudentAcceptanceNotification(context: Context, currentRole: com.example.data.model.UserRole?) {
        // Only deliver standard notification to student device
        if (currentRole != com.example.data.model.UserRole.STUDENT) {
            return
        }

        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, STUDENT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Request Accepted")
                .setContentText("Your request has been accepted. The driver is on the way.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setVibrate(longArrayOf(0)) // NO vibration
                .setSound(null)            // NO sound
                .build()

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(STUDENT_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopAlert(context: Context) {
        _activeAlertRequest.value = null
        lastAlertedRequestId = null
        stopSoundAndVibration()

        try {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopSoundAndVibration() {
        stopRingtonePreview()
        try {
            activeMediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
            activeMediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            activeVibrator?.cancel()
            activeVibrator = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
