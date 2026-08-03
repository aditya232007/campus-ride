package com.example.ui.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

object PermissionUtils {
    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isGpsEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
               lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.canUseFullScreenIntent() ?: true
        } else {
            true
        }
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            true
        }
    }

    fun requestDisableBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                openAppSettings(context)
            }
        } else {
            openAppSettings(context)
        }
    }

    fun openAutoStartSettings(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intent = Intent()
        try {
            when {
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                    intent.component = android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                }
                manufacturer.contains("oppo") -> {
                    intent.component = android.content.ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                }
                manufacturer.contains("vivo") -> {
                    intent.component = android.content.ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                }
                manufacturer.contains("oneplus") -> {
                    intent.component = android.content.ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActActivity")
                }
                manufacturer.contains("samsung") -> {
                    intent.component = android.content.ComponentName("com.samsung.android.looper", "com.samsung.android.sm.ui.battery.BatteryActivity")
                }
                else -> {
                    openAppSettings(context)
                    return
                }
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            openAppSettings(context)
        }
    }

    fun openFullScreenIntentSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                openOverlaySettings(context)
            }
        } else {
            openOverlaySettings(context)
        }
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }

    fun openLocationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        context.startActivity(intent)
    }

    fun openOverlaySettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        } else {
            openAppSettings(context)
        }
    }
}

@Composable
fun StudentPermissionGuard(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var hasLocation by remember { mutableStateOf(PermissionUtils.hasLocationPermission(context)) }
    var isGpsOn by remember { mutableStateOf(PermissionUtils.isGpsEnabled(context)) }

    fun refreshStatus() {
        hasLocation = PermissionUtils.hasLocationPermission(context)
        isGpsOn = PermissionUtils.isGpsEnabled(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    if (hasLocation && isGpsOn) {
        content()
    } else {
        val (title, description, primaryLabel, primaryAction, icon) = when {
            !isGpsOn -> Quintuple(
                "Location Services Required",
                "Location Services (GPS) are currently turned off. Campus Ride requires GPS to verify whether you are within 70 meters of the IIIT Bhagalpur Main Gate before requesting a ride.",
                "Turn On GPS",
                { PermissionUtils.openLocationSettings(context) },
                Icons.Default.GpsFixed
            )
            else -> Quintuple(
                "Location Permission Required",
                "Campus Ride requires access to your location to verify your proximity to the IIIT Bhagalpur Main Gate.",
                "Grant Location Permission",
                { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                Icons.Default.LocationOn
            )
        }

        PermissionRequiredPopupScreen(
            title = title,
            description = description,
            icon = icon,
            primaryButtonLabel = primaryLabel,
            onPrimaryAction = primaryAction,
            onRetry = { refreshStatus() }
        )
    }
}

@Composable
fun FacultyPermissionGuard(
    content: @Composable () -> Unit
) {
    // Faculty does NOT require any permissions. Direct access to Faculty Dashboard.
    content()
}

@Composable
fun DriverPermissionGuard(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var hasNotif by remember { mutableStateOf(PermissionUtils.hasNotificationPermission(context)) }
    var hasLocation by remember { mutableStateOf(PermissionUtils.hasLocationPermission(context)) }
    var isGpsOn by remember { mutableStateOf(PermissionUtils.isGpsEnabled(context)) }
    var canOverlay by remember { mutableStateOf(PermissionUtils.canDrawOverlays(context)) }
    var canFullScreenIntent by remember { mutableStateOf(PermissionUtils.canUseFullScreenIntent(context)) }
    var isUnrestrictedBattery by remember { mutableStateOf(PermissionUtils.isBatteryOptimizationIgnored(context)) }

    fun refreshStatus() {
        hasNotif = PermissionUtils.hasNotificationPermission(context)
        hasLocation = PermissionUtils.hasLocationPermission(context)
        isGpsOn = PermissionUtils.isGpsEnabled(context)
        canOverlay = PermissionUtils.canDrawOverlays(context)
        canFullScreenIntent = PermissionUtils.canUseFullScreenIntent(context)
        isUnrestrictedBattery = PermissionUtils.isBatteryOptimizationIgnored(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { refreshStatus() }

    // Core requirements: Notification + Location + GPS + Full Screen Intent Capability
    if (hasNotif && hasLocation && isGpsOn && canFullScreenIntent) {
        content()
    } else {
        val (title, description, primaryLabel, primaryAction, icon) = when {
            !isGpsOn -> Quintuple(
                "Location Services Required",
                "Location Services (GPS) are currently turned off. The Driver Terminal requires GPS to update golf cart coordinates on campus.",
                "Turn On GPS",
                { PermissionUtils.openLocationSettings(context) },
                Icons.Default.GpsFixed
            )
            !hasLocation -> Quintuple(
                "Location Permission Required",
                "Driver Terminal requires location permission to track golf cart position and provide real-time updates to riders.",
                "Grant Location Permission",
                { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                Icons.Default.LocationOn
            )
            !hasNotif -> Quintuple(
                "Notification Permission Required",
                "Driver Terminal requires notification permission to trigger instant urgent audio alerts for new ride requests.",
                "Grant Notification Permission",
                {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        PermissionUtils.openAppSettings(context)
                    }
                },
                Icons.Default.NotificationsActive
            )
            !canFullScreenIntent -> Quintuple(
                "Full Screen Alert Permission Required",
                "On Android 14+, Driver Terminal requires Full Screen Intent permission to trigger urgent incoming ride request alerts when the device is locked.",
                "Enable Full Screen Permission",
                { PermissionUtils.openFullScreenIntentSettings(context) },
                Icons.Default.Shield
            )
            else -> Quintuple(
                "Display Over Other Apps Required",
                "Driver Terminal works best with 'Display over other apps' enabled to pop up incoming alerts floating over other apps.",
                "Open Overlay Settings",
                { PermissionUtils.openOverlaySettings(context) },
                Icons.Default.Shield
            )
        }

        PermissionRequiredPopupScreen(
            title = title,
            description = description,
            icon = icon,
            primaryButtonLabel = primaryLabel,
            onPrimaryAction = primaryAction,
            onRetry = { refreshStatus() }
        )
    }
}

@Composable
private fun PermissionRequiredPopupScreen(
    title: String,
    description: String,
    icon: ImageVector,
    primaryButtonLabel: String,
    onPrimaryAction: () -> Unit,
    onRetry: () -> Unit
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = description,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    // Primary Action Button (Grant or Open Specific Setting)
                    Button(
                        onClick = onPrimaryAction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = primaryButtonLabel,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Open App Settings Button
                    OutlinedButton(
                        onClick = { PermissionUtils.openAppSettings(context) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open Settings",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Retry Button
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Retry",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)
