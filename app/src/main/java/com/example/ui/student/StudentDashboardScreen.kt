package com.example.ui.student

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import kotlin.math.roundToInt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GolfCartStatus
import com.example.data.model.RideRequestStatus
import com.example.data.model.ScheduleStatus
import com.example.data.repository.CampusRideRepository
import com.example.location.GeofenceManager
import com.example.ui.theme.StatusAssigned
import com.example.ui.theme.StatusAvailable
import com.example.ui.theme.StatusOffline
import com.example.ui.theme.StatusWaiting
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentDashboardScreen(
    repository: CampusRideRepository,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val cartState by repository.golfCartState.collectAsState()
    val isDriverAvailable by repository.isDriverAvailable.collectAsState()
    val activeRequest by repository.activeStudentRequest.collectAsState()
    val cooldownSeconds by repository.cooldownSeconds.collectAsState()
    val overrideHours by repository.overrideWorkingHours.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var isSendingRequest by remember { mutableStateOf(false) }

    val scheduleStatus = ScheduleStatus.getCurrentStatus(overrideHours)

    // Real-time GPS Location listener
    var userLatState by remember { mutableStateOf<Double?>(null) }
    var userLngState by remember { mutableStateOf<Double?>(null) }

    DisposableEffect(context) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                userLatState = location.latitude
                userLngState = location.longitude
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            val gpsLoc = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLoc = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val best = when {
                gpsLoc != null && netLoc != null -> if (gpsLoc.time > netLoc.time) gpsLoc else netLoc
                gpsLoc != null -> gpsLoc
                else -> netLoc
            }
            if (best != null) {
                userLatState = best.latitude
                userLngState = best.longitude
            }

            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0.5f,
                    listener,
                    Looper.getMainLooper()
                )
            }
            if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0.5f,
                    listener,
                    Looper.getMainLooper()
                )
            }
        } catch (e: Exception) {
            // Location access safe fallback
        }

        onDispose {
            try {
                locationManager?.removeUpdates(listener)
            } catch (e: Exception) { }
        }
    }

    val activeLat = userLatState ?: GeofenceManager.GATE_LAT
    val activeLng = userLngState ?: GeofenceManager.GATE_LNG

    val measuredDistanceMeters = GeofenceManager.calculateDistanceMeters(activeLat, activeLng).roundToInt()
    val isNearGate = measuredDistanceMeters <= GeofenceManager.MAX_GEOFENCE_METERS

    val canNotifyDriver = (GeofenceManager.isTestModeEnabled || isNearGate) &&
            scheduleStatus.isAvailable &&
            isDriverAvailable &&
            cooldownSeconds == 0 &&
            !isSendingRequest

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFDC2626)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "CR",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Campus Ride",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(StatusAvailable)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Student Portal",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StatusAvailable
                                )
                            }
                        }
                    }
                },
                actions = {
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
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Schedule Notice Banner if off-duty
            if (!scheduleStatus.isAvailable) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFFEF2F2),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCA5A5)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFFDC2626)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = scheduleStatus.message,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF991B1B)
                        )
                    }
                }
            }

            // 1. Pickup Point Card
            DashboardInfoCard(
                icon = Icons.Default.PinDrop,
                iconTint = Color(0xFF2563EB),
                iconBg = Color(0xFFEFF6FF),
                label = "Pickup Point",
                value = "IIIT Bhagalpur Main Gate"
            )

            // 2. Distance From Gate Card
            DashboardInfoCard(
                icon = Icons.Default.LocationOn,
                iconTint = Color(0xFF16A34A),
                iconBg = Color(0xFFF0FDF4),
                label = "Distance from Gate",
                value = "$measuredDistanceMeters m"
            )

            // 3. Pickup Status Card (Inside 70m / Outside 70m)
            val isInsideZone = isNearGate
            DashboardInfoCard(
                icon = if (isInsideZone) Icons.Default.CheckCircle else Icons.Default.Info,
                iconTint = if (isInsideZone) StatusAvailable else StatusOffline,
                iconBg = if (isInsideZone) Color(0xFFDCFCE7) else Color(0xFFFEF2F2),
                label = "Pickup Status",
                value = if (isInsideZone) "Inside Pickup Zone" else "Outside Pickup Zone",
                badgeText = if (isInsideZone) "Inside 70m" else "Outside 70m",
                badgeColor = if (isInsideZone) StatusAvailable else StatusOffline,
                badgeBg = if (isInsideZone) Color(0xFFDCFCE7) else Color(0xFFFEF2F2)
            )

            // 4. Golf Cart Card
            val isCartAssignedAndOnline = cartState != null &&
                    cartState?.status != GolfCartStatus.OFFLINE &&
                    isDriverAvailable
            val cartDisplayName = if (isCartAssignedAndOnline) {
                cartState?.cartName ?: "Campus Golf Cart"
            } else {
                "Waiting for Assignment"
            }
            val cartBadgeText = when {
                !isDriverAvailable || cartState?.status == GolfCartStatus.OFFLINE -> "Offline"
                isCartAssignedAndOnline -> "Assigned"
                else -> "Waiting"
            }
            val cartBadgeColor = when {
                !isDriverAvailable || cartState?.status == GolfCartStatus.OFFLINE -> StatusOffline
                isCartAssignedAndOnline -> StatusAssigned
                else -> StatusWaiting
            }
            val cartBadgeBg = when {
                !isDriverAvailable || cartState?.status == GolfCartStatus.OFFLINE -> Color(0xFFFEF2F2)
                isCartAssignedAndOnline -> Color(0xFFEFF6FF)
                else -> Color(0xFFFEF3C7)
            }

            DashboardInfoCard(
                icon = Icons.Default.DirectionsBus,
                iconTint = cartBadgeColor,
                iconBg = cartBadgeBg,
                label = "Golf Cart",
                value = cartDisplayName,
                badgeText = cartBadgeText,
                badgeColor = cartBadgeColor,
                badgeBg = cartBadgeBg
            )

            // 5. Live Golf Cart Distance & Telemetry Movement Card
            val currentCart = cartState
            val liveCartDistanceText = if (isCartAssignedAndOnline && currentCart?.latitude != null && currentCart.longitude != null) {
                val distMeters = GeofenceManager.calculateDistanceMeters(currentCart.latitude, currentCart.longitude, activeLat, activeLng).roundToInt()
                "$distMeters m away"
            } else {
                "Telemetry Inactive"
            }

            val liveMovementStr = if (isCartAssignedAndOnline) {
                currentCart?.relativeMovement ?: "Stationary"
            } else "N/A"

            val movementBadgeColor = when (liveMovementStr) {
                "Coming Towards You" -> StatusAvailable
                "Moving Away" -> StatusOffline
                else -> StatusWaiting
            }

            val movementBadgeBg = when (liveMovementStr) {
                "Coming Towards You" -> Color(0xFFDCFCE7)
                "Moving Away" -> Color(0xFFFEF2F2)
                else -> Color(0xFFFEF3C7)
            }

            DashboardInfoCard(
                icon = Icons.Default.NearMe,
                iconTint = movementBadgeColor,
                iconBg = movementBadgeBg,
                label = "Golf Cart Distance",
                value = liveCartDistanceText,
                badgeText = liveMovementStr,
                badgeColor = movementBadgeColor,
                badgeBg = movementBadgeBg
            )

            // 6. Dynamic ETA Card (No fake ETAs when stationary)
            val etaDisplayValue = if (isCartAssignedAndOnline && cartState?.etaMinutes != null) {
                "${cartState?.etaMinutes} min"
            } else {
                "ETA Unavailable"
            }

            DashboardInfoCard(
                icon = Icons.Default.Schedule,
                iconTint = if (isCartAssignedAndOnline && cartState?.etaMinutes != null) StatusWaiting else Color(0xFF64748B),
                iconBg = if (isCartAssignedAndOnline && cartState?.etaMinutes != null) Color(0xFFFEF3C7) else Color(0xFFF1F5F9),
                label = "ETA",
                value = etaDisplayValue
            )

            // Active Ride Request Status Banner
            AnimatedVisibility(
                visible = activeRequest != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                activeRequest?.let { req ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when (req.status) {
                                RideRequestStatus.ACCEPTED -> Color(0xFFEFF6FF)
                                RideRequestStatus.COMPLETED -> Color(0xFFF0FDF4)
                                else -> Color(0xFFFFFBEB)
                            }
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            when (req.status) {
                                RideRequestStatus.ACCEPTED -> Color(0xFF93C5FD)
                                RideRequestStatus.COMPLETED -> Color(0xFF86EFAC)
                                else -> Color(0xFDFCD34D)
                            }
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (req.status == RideRequestStatus.ACCEPTED) Icons.Default.CheckCircle else Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = when (req.status) {
                                    RideRequestStatus.ACCEPTED -> StatusAssigned
                                    RideRequestStatus.COMPLETED -> StatusAvailable
                                    else -> StatusWaiting
                                },
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (req.status == RideRequestStatus.ACCEPTED) "Request Accepted" else "Request Sent",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val cartNameText = req.assignedCartName ?: "Waiting for assignment"
                                val etaText = if (isCartAssignedAndOnline) "${cartState?.etaMinutes ?: 2} min" else "Not Available"
                                Text(
                                    text = if (req.status == RideRequestStatus.ACCEPTED)
                                        "Driver is en route to Main Gate."
                                    else
                                        "Status: Pending • Cart: $cartNameText • ETA: $etaText",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Large Red "Notify Driver" Button Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (cooldownSeconds > 0) {
                    val mins = cooldownSeconds / 60
                    val secs = cooldownSeconds % 60
                    val formattedTime = String.format("%02d:%02d", mins, secs)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = Color(0xFFDC2626),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Cooldown Active: $formattedTime",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFDC2626)
                        )
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            isSendingRequest = true
                            val result = repository.sendStudentRideRequest(
                                studentLat = activeLat,
                                studentLng = activeLng
                            )
                            isSendingRequest = false
                            result.onSuccess {
                                snackbarHostState.showSnackbar("Driver has been successfully notified.")
                            }.onFailure { ex ->
                                snackbarHostState.showSnackbar(ex.message ?: "Could not notify driver")
                            }
                        }
                    },
                    enabled = canNotifyDriver,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFE2E8F0),
                        disabledContentColor = Color(0xFF94A3B8)
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp)
                ) {
                    if (isSendingRequest) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Sending Request...",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = if (cooldownSeconds > 0) "Cooldown Active" else "Notify Driver",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Premium explanatory message when disabled
                if (!canNotifyDriver && !isSendingRequest) {
                    val disabledReason = when {
                        !GeofenceManager.isTestModeEnabled && !isNearGate -> "Move within 70 m of the Main Gate to enable."
                        !scheduleStatus.isAvailable -> scheduleStatus.message
                        !isDriverAvailable -> "Golf cart drivers are currently offline."
                        cooldownSeconds > 0 -> "Please wait for cooldown timer to complete."
                        else -> "Move within 70 m of the Main Gate to enable."
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = disabledReason,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF64748B),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardInfoCard(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    label: String,
    value: String,
    badgeText: String? = null,
    badgeColor: Color = Color.Gray,
    badgeBg: Color = Color.LightGray
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF64748B)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (badgeText != null) {
                Surface(
                    shape = CircleShape,
                    color = badgeBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.25f))
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
