package com.example.ui.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.UserRole
import com.example.notification.CriticalAlertManager
import com.example.notification.FcmRoleNotificationManager
import kotlinx.coroutines.launch

@Composable
fun DriverNotificationSetupScreen(
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Ensure notification channel initialization and role sync
    LaunchedEffect(Unit) {
        CriticalAlertManager.initNotificationChannel(context)
        FcmRoleNotificationManager.syncRoleFcmSubscription(context, UserRole.DRIVER)
    }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    var isRingerNormal by remember { mutableStateOf(audioManager?.ringerMode == AudioManager.RINGER_MODE_NORMAL) }
    var hasNotifPermission by remember { mutableStateOf(PermissionUtils.hasNotificationPermission(context)) }
    var isChannelEnabled by remember { mutableStateOf(PermissionUtils.isDriverNotificationChannelEnabled(context)) }
    var hasLocation by remember { mutableStateOf(PermissionUtils.hasLocationPermission(context)) }
    var isGpsOn by remember { mutableStateOf(PermissionUtils.isGpsEnabled(context)) }
    var isBatteryUnrestricted by remember { mutableStateOf(PermissionUtils.isBatteryOptimizationIgnored(context)) }
    var isBgAvailable by remember { mutableStateOf(PermissionUtils.isBackgroundOperationAvailable(context)) }
    var canOverlay by remember { mutableStateOf(PermissionUtils.canDrawOverlays(context)) }
    var canFullScreen by remember { mutableStateOf(PermissionUtils.canUseFullScreenIntent(context)) }
    var isSoundEnabled by remember { mutableStateOf(PermissionUtils.isChannelSoundEnabled(context)) }
    var isFcmRegistered by remember { mutableStateOf(PermissionUtils.isFcmTokenRegistered(context)) }

    var showDeviceInstructions by remember { mutableStateOf(false) }

    fun refreshAllStatuses() {
        CriticalAlertManager.initNotificationChannel(context)
        isRingerNormal = audioManager?.ringerMode == AudioManager.RINGER_MODE_NORMAL
        hasNotifPermission = PermissionUtils.hasNotificationPermission(context)
        isChannelEnabled = PermissionUtils.isDriverNotificationChannelEnabled(context)
        hasLocation = PermissionUtils.hasLocationPermission(context)
        isGpsOn = PermissionUtils.isGpsEnabled(context)
        isBatteryUnrestricted = PermissionUtils.isBatteryOptimizationIgnored(context)
        isBgAvailable = PermissionUtils.isBackgroundOperationAvailable(context)
        canOverlay = PermissionUtils.canDrawOverlays(context)
        canFullScreen = PermissionUtils.canUseFullScreenIntent(context)
        isSoundEnabled = PermissionUtils.isChannelSoundEnabled(context)
        isFcmRegistered = PermissionUtils.isFcmTokenRegistered(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshAllStatuses()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { refreshAllStatuses() }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { refreshAllStatuses() }

    val notifReady = hasNotifPermission && isChannelEnabled
    val locationReady = hasLocation && isGpsOn
    val backgroundReady = isBgAvailable
    val batteryReady = isBatteryUnrestricted
    val overlayReady = canOverlay && canFullScreen
    val soundReady = isRingerNormal && isSoundEnabled

    val allSetupItemsReady = notifReady && locationReady && backgroundReady && batteryReady && overlayReady && soundReady
    val allCapabilitiesReady = allSetupItemsReady && isFcmRegistered

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(if (allCapabilitiesReady) Color(0xFFDCFCE7) else MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (allCapabilitiesReady) Icons.Default.CheckCircle else Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (allCapabilitiesReady) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = if (allCapabilitiesReady) "Driver Setup Complete" else "Complete Driver Setup",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (allCapabilitiesReady) Color(0xFF15803D) else MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (allCapabilitiesReady) {
                        "Your phone is ready to receive Driver ride requests."
                    } else {
                        "Allow these settings so you never miss a ride request."
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Setup Checklist Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "REQUIRED SETTINGS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 0.5.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // 1. Notifications
                        CompactCheckRow(
                            title = "Notifications",
                            subtitle = if (notifReady) "Notifications allowed" else if (!hasNotifPermission) "Required to show incoming rides" else "Notification channel disabled",
                            icon = Icons.Default.NotificationsActive,
                            isOk = notifReady,
                            actionLabel = if (!hasNotifPermission) "Allow" else if (!isChannelEnabled) "Configure" else null,
                            onAction = {
                                if (!hasNotifPermission) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        PermissionUtils.openAppSettings(context)
                                    }
                                } else {
                                    PermissionUtils.openNotificationChannelSettings(context)
                                }
                            }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // 2. Location/GPS
                        CompactCheckRow(
                            title = "Location & GPS",
                            subtitle = when {
                                !hasLocation -> "Location permission required"
                                !isGpsOn -> "Turn on GPS in device settings"
                                else -> "Location & GPS active"
                            },
                            icon = if (!hasLocation) Icons.Default.LocationOn else Icons.Default.GpsFixed,
                            isOk = locationReady,
                            actionLabel = if (!hasLocation) "Allow" else if (!isGpsOn) "Turn On GPS" else null,
                            onAction = {
                                if (!hasLocation) {
                                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                } else {
                                    PermissionUtils.openLocationSettings(context)
                                }
                            }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // 3. Background operation
                        CompactCheckRow(
                            title = "Background Operation",
                            subtitle = if (backgroundReady) "Background activity allowed" else "Allow app to remain active in background",
                            icon = Icons.Default.Shield,
                            isOk = backgroundReady,
                            actionLabel = if (!backgroundReady) "Open Settings" else null,
                            onAction = { PermissionUtils.openAutoStartSettings(context) }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // 4. Battery Saver
                        CompactCheckRow(
                            title = "Battery Optimization",
                            subtitle = if (batteryReady) "Unrestricted battery usage" else "Allow unrestricted battery usage for instant alerts",
                            icon = Icons.Default.BatterySaver,
                            isOk = batteryReady,
                            actionLabel = if (!batteryReady) "Allow" else null,
                            onAction = { PermissionUtils.requestDisableBatteryOptimization(context) }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // 5. Display over apps / Full screen alert
                        CompactCheckRow(
                            title = "Full Screen Alerts",
                            subtitle = when {
                                !canFullScreen -> "Full screen alert permission required"
                                !canOverlay -> "Required to display incoming ride requests over other apps"
                                else -> "Full-screen alert overlay ready"
                            },
                            icon = Icons.Default.Shield,
                            isOk = overlayReady,
                            actionLabel = if (!canFullScreen) "Enable" else if (!canOverlay) "Enable" else null,
                            onAction = {
                                if (!canFullScreen) {
                                    PermissionUtils.openFullScreenIntentSettings(context)
                                } else {
                                    PermissionUtils.openOverlaySettings(context)
                                }
                            }
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // 6. Sound Mode
                        CompactCheckRow(
                            title = "Phone Sound Mode",
                            subtitle = when {
                                !isRingerNormal -> "Turn on Sound to receive ride alerts"
                                !isSoundEnabled -> "Alert channel sound is muted"
                                else -> "Sound enabled for ride alerts"
                            },
                            icon = if (isRingerNormal) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            isOk = soundReady,
                            actionLabel = if (!isRingerNormal) "Open Sound Settings" else if (!isSoundEnabled) "Configure" else null,
                            onAction = {
                                if (!isRingerNormal) {
                                    try {
                                        val intent = Intent(Settings.ACTION_SOUND_SETTINGS).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        PermissionUtils.openAppSettings(context)
                                    }
                                } else {
                                    PermissionUtils.openNotificationChannelSettings(context)
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Device instructions toggle
                if (!backgroundReady || !batteryReady) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDeviceInstructions = !showDeviceInstructions }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Device-specific background instructions",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (showDeviceInstructions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    AnimatedVisibility(visible = showDeviceInstructions) {
                        Text(
                            text = "If your phone limits background apps (e.g. Xiaomi, Samsung, Vivo, OPPO, OnePlus), enable 'Auto-Start' or 'Allow Background Activity' in your device settings.",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 6.dp),
                            lineHeight = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Bottom Actions with safe navigation bar padding
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { refreshAllStatuses() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Check Again",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Check Again", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onContinue,
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Continue to Driver Dashboard", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactCheckRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isOk: Boolean,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (isOk) Color(0xFFDCFCE7) else Color(0xFFFEF3C7)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isOk) Icons.Default.CheckCircle else icon,
                contentDescription = null,
                tint = if (isOk) Color(0xFF16A34A) else Color(0xFFD97706),
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = Color.Gray
            )
        }

        if (actionLabel != null) {
            Spacer(modifier = Modifier.width(6.dp))
            OutlinedButton(
                onClick = onAction,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(actionLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
