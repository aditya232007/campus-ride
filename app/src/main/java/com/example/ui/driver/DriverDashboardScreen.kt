package com.example.ui.driver

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.example.ui.components.LiveRouteTrackingCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.RequesterType
import com.example.data.model.RideRequest
import com.example.data.model.RideRequestStatus
import com.example.data.model.UserRole
import com.example.data.repository.CampusRideRepository
import com.example.location.CampusLandmarkZone
import com.example.location.DriverLocationTracker
import androidx.compose.material.icons.filled.Place

import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Warning
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

private data class GeofenceDisplayState(
    val bg: Color,
    val iconBg: Color,
    val textColor: Color,
    val title: String,
    val subtitle: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverDashboardScreen(
    repository: CampusRideRepository,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenRingtoneSettings: () -> Unit = {}
) {
    LaunchedEffect(Unit) {
        repository.saveRole(UserRole.DRIVER)
    }

    val cartState by repository.golfCartState.collectAsState()
    val requests by repository.requests.collectAsState()
    val overrideHours by repository.overrideWorkingHours.collectAsState()
    val schedule = com.example.data.model.ScheduleStatus.getCurrentStatus(overrideHours)
    val criticalAlertRequest by com.example.notification.CriticalAlertManager.activeAlertRequest.collectAsState()

    val driverDutyState by repository.driverDutyState.collectAsState()
    val lunchBreakRemainingSeconds by repository.lunchBreakRemainingSeconds.collectAsState()
    val isOnLunchBreak = (driverDutyState == "Lunch Break" || lunchBreakRemainingSeconds > 0)

    val isInsideServiceArea by repository.isInsideGeofence.collectAsState()
    val hasGpsLocation by repository.hasGpsLocation.collectAsState()
    val distanceToLibrary by repository.distanceToLibraryMeters.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    val tracker = remember(context) { DriverLocationTracker(context) }
    var hasLocationPermission by remember { mutableStateOf(tracker.isLocationPermissionGranted()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            hasLocationPermission = true
        } else {
            hasLocationPermission = false
            repository.onGpsDisabledOrPermissionMissing()
        }
    }

    LaunchedEffect(Unit) {
        val granted = tracker.isLocationPermissionGranted()
        hasLocationPermission = granted
        if (!granted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    DisposableEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            tracker.startTracking(
                onLocationUpdate = { location ->
                    val speedKmH = (location.speed * 3.6f).roundToInt()
                    repository.updateDriverGpsLocation(
                        lat = location.latitude,
                        lng = location.longitude,
                        speedKmH = speedKmH,
                        bearing = location.bearing,
                        accuracy = location.accuracy
                    )
                },
                onDisabledOrError = {
                    repository.onGpsDisabledOrPermissionMissing()
                }
            )
        } else {
            repository.onGpsDisabledOrPermissionMissing()
        }

        onDispose {
            tracker.stopTracking()
        }
    }

    var showLunchBreakConfirmDialog by remember { mutableStateOf(false) }

    val pendingRequests = requests.filter { it.status == RideRequestStatus.PENDING }
        .sortedWith(
            compareByDescending<RideRequest> { it.isPriority }
                .thenByDescending { it.studentsWaiting }
                .thenBy { it.timestamp }
        )
    val activeAcceptedRequests = requests.filter { it.status == RideRequestStatus.ACCEPTED }

    val isDriverAvailable = (driverDutyState == "Available")
    val canStartLunchBreak = isDriverAvailable && activeAcceptedRequests.isEmpty() && pendingRequests.isEmpty()

    val displayList = if (isOnLunchBreak) emptyList() else activeAcceptedRequests + pendingRequests

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DirectionsBus,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Driver Terminal",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "IIIT Bhagalpur Transport",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenRingtoneSettings) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = "Ringtone & Alert Sound",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(20.dp)
            ) {
                // Missing Permission Banner check
                val context = androidx.compose.ui.platform.LocalContext.current
                val hasNotifPermission = remember {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    } else true
                }

                if (!hasNotifPermission && !isOnLunchBreak) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFFEF2F2),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCA5A5)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NotificationsActive,
                                    contentDescription = "Permission Warning",
                                    tint = Color(0xFFDC2626)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Alert Notifications Disabled",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF991B1B)
                                    )
                                    Text(
                                        text = "Allow notifications for loud incoming pickup alerts",
                                        fontSize = 11.sp,
                                        color = Color(0xFF7F1D1D)
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                            ) {
                                Text("Settings", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // 🚗 Campus Cart Connected Route Location Timeline Card
                LiveRouteTrackingCard(
                    cartState = cartState,
                    isDriverAvailable = isDriverAvailable
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (isOnLunchBreak) {
                    val minutes = lunchBreakRemainingSeconds / 60
                    val seconds = lunchBreakRemainingSeconds % 60
                    val timeRemainingFormatted = String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFFDE68A)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(20.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Restaurant,
                                    contentDescription = "Lunch Break",
                                    tint = Color(0xFFD97706),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "🍽 Lunch Break",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp,
                                    color = Color(0xFFB45309)
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Time Remaining",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF92400E)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = timeRemainingFormatted,
                                fontSize = 42.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFD97706),
                                letterSpacing = 2.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "You are currently unavailable for ride requests. Your status will automatically change back to Available when the timer reaches 00:00.",
                                fontSize = 11.sp,
                                color = Color(0xFFB45309),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    // Automatic Working Schedule Status Card
                    val (cardBg, iconBg, textColor) = when (schedule.dutyState) {
                        com.example.data.model.ScheduleDutyState.ON_DUTY -> Triple(Color(0xFFF0FDF4), Color(0xFF16A34A), Color(0xFF15803D))
                        com.example.data.model.ScheduleDutyState.LUNCH_BREAK -> Triple(Color(0xFFFFFBEB), Color(0xFFD97706), Color(0xFFB45309))
                        com.example.data.model.ScheduleDutyState.OFF_DUTY -> Triple(Color(0xFFFEF2F2), Color(0xFFDC2626), Color(0xFF991B1B))
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(18.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(iconBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (schedule.isAvailable) Icons.Default.GpsFixed else Icons.Default.PowerSettingsNew,
                                    contentDescription = "Operating Status",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = schedule.statusBadgeLabel,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 17.sp,
                                    color = textColor
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = schedule.message,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Driver Service Area Geofence Status Card
                    val geoState = when {
                        !hasGpsLocation -> GeofenceDisplayState(
                            bg = Color(0xFFFEF2F2),
                            iconBg = Color(0xFFDC2626),
                            textColor = Color(0xFF991B1B),
                            title = "Location Unavailable",
                            subtitle = "Enable GPS & location permissions to receive ride requests"
                        )
                        isInsideServiceArea -> GeofenceDisplayState(
                            bg = Color(0xFFF0FDF4),
                            iconBg = Color(0xFF16A34A),
                            textColor = Color(0xFF15803D),
                            title = "Inside Service Area",
                            subtitle = "Available for ride requests" + (distanceToLibrary?.let { dist ->
                                val distStr = if (dist < 1000) "${dist.roundToInt()} m" else String.format(java.util.Locale.getDefault(), "%.1f km", dist / 1000.0)
                                " • $distStr from Vikramshila Library"
                            } ?: "")
                        )
                        else -> GeofenceDisplayState(
                            bg = Color(0xFFFFFBEB),
                            iconBg = Color(0xFFD97706),
                            textColor = Color(0xFFB45309),
                            title = "Outside Service Area",
                            subtitle = "Ride requests unavailable" + (distanceToLibrary?.let { dist ->
                                val distStr = if (dist < 1000) "${dist.roundToInt()} m" else String.format(java.util.Locale.getDefault(), "%.1f km", dist / 1000.0)
                                " • $distStr from Vikramshila Library"
                            } ?: "")
                        )
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = geoState.bg),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(18.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(geoState.iconBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when {
                                        !hasGpsLocation -> Icons.Default.GpsOff
                                        isInsideServiceArea -> Icons.Default.LocationOn
                                        else -> Icons.Default.Warning
                                    },
                                    contentDescription = "Geofence Status",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = geoState.title,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 17.sp,
                                    color = geoState.textColor
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = geoState.subtitle,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Start Lunch Break Button
                    Button(
                        onClick = { showLunchBreakConfirmDialog = true },
                        enabled = canStartLunchBreak,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF59E0B),
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFFE2E8F0),
                            disabledContentColor = Color(0xFF94A3B8)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restaurant,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "🍽 Start Lunch Break",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Cart Status Summary Row
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "PENDING REQUESTS",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isOnLunchBreak) "0" else "${pendingRequests.size}",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(28.dp)
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "ACTIVE ACCEPTED",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isOnLunchBreak) "0" else "${activeAcceptedRequests.size}",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 20.sp,
                                color = Color(0xFF16A34A)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Pickup Requests Section Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Pickup Queue",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (!isOnLunchBreak && pendingRequests.isNotEmpty()) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "${pendingRequests.size} Pending",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isOnLunchBreak) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = Color(0xFFD97706),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Lunch Break Active",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "New ride requests are paused until your lunch break ends.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else if (displayList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.DirectionsBus,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No active or pending pickup requests",
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(displayList, key = { it.id }) { req ->
                            DriverRequestItemCard(
                                request = req,
                                onAccept = { repository.acceptRideRequest(req.id) }
                            )
                        }
                    }
                }
            }
        }

        if (showLunchBreakConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showLunchBreakConfirmDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Restaurant,
                        contentDescription = null,
                        tint = Color(0xFFD97706),
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        text = "Start Lunch Break?",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = "You will become unavailable for ride requests for the next 1 hour.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showLunchBreakConfirmDialog = false
                            repository.startLunchBreak()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Start Lunch Break", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { showLunchBreakConfirmDialog = false },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel")
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp)
            )
        }

        if (!isOnLunchBreak) {
            IncomingDriverAlertOverlay(
                activeRequest = criticalAlertRequest,
                onAccept = { reqId ->
                    repository.acceptRideRequest(reqId)
                },
                onDecline = { reqId ->
                    repository.declineRideRequest(reqId)
                }
            )
        }
    }
}

@Composable
fun DriverRequestItemCard(
    request: RideRequest,
    onAccept: () -> Unit
) {
    val isFaculty = (request.requesterType == RequesterType.FACULTY)

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (isFaculty) MaterialTheme.colorScheme.primary else Color(0xFFEFF6FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isFaculty) Icons.Default.School else Icons.Default.Person,
                            contentDescription = null,
                            tint = if (isFaculty) Color.White else Color(0xFF2563EB),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isFaculty) MaterialTheme.colorScheme.primary else Color(0xFFDBEAFE)
                        ) {
                            Text(
                                text = if (isFaculty) "Faculty Priority Request" else "Student Request",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isFaculty) Color.White else Color(0xFF1E40AF),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = request.pickupLocationEnum.emoji,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = request.pickupLocationEnum.displayName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (!isFaculty) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Group,
                                    contentDescription = null,
                                    tint = if (request.studentsWaiting >= 5) Color(0xFFDC2626) else Color(0xFF2563EB),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "${request.studentsWaiting} ${if (request.studentsWaiting == 1) "student" else "students"} waiting",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (request.studentsWaiting >= 5) Color(0xFFDC2626) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (request.studentsWaiting >= 5) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFFFEE2E2)
                                    ) {
                                        Text(
                                            text = "High Waiting",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFDC2626),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = when (request.status) {
                            RideRequestStatus.ACCEPTED -> Color(0xFFDCFCE7)
                            RideRequestStatus.COMPLETED -> Color(0xFFF1F5F9)
                            else -> Color(0xFFFEF2F2)
                        }
                    ) {
                        Text(
                            text = request.status.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (request.status) {
                                RideRequestStatus.ACCEPTED -> Color(0xFF15803D)
                                RideRequestStatus.COMPLETED -> Color(0xFF64748B)
                                else -> Color(0xFF991B1B)
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = request.formattedTime,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (request.status == RideRequestStatus.PENDING) {
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onAccept,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Accept",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "ACCEPT REQUEST", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            } else if (request.status == RideRequestStatus.ACCEPTED) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFDCFCE7),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF16A34A),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Accepted • Golf Cart En Route",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color(0xFF15803D)
                        )
                    }
                }
            }
        }
    }
}
