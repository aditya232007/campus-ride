package com.example.ui.student

import android.content.Context
import android.util.Log
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.updateTransition
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Refresh
import com.example.ui.components.LiveRouteTrackingCard
import com.example.ui.components.CampusCartCard
import com.example.ui.components.CampusPullToRefreshBox
import com.example.data.model.UserRole
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.example.data.model.PickupLocation
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
    val cart1State by repository.cart1State.collectAsState()
    val cart2State by repository.cart2State.collectAsState()
    var selectedCartTab by remember { mutableStateOf("cart_1") }
    val activeCartState = if (selectedCartTab == "cart_1") cart1State else cart2State

    val isDriverAvailable by repository.isDriverAvailable.collectAsState()
    val activeRequest by repository.activeStudentRequest.collectAsState()
    val cooldownSeconds by repository.cooldownSeconds.collectAsState()
    val overrideHours by repository.overrideWorkingHours.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var isRefreshing by remember { mutableStateOf(false) }
    var isSendingRequest by remember { mutableStateOf(false) }
    var showStudentsWaitingSheet by remember { mutableStateOf(false) }
    var selectedStudentsCount by remember { mutableStateOf(1) }
    val studentPickupLocation = PickupLocation.GATE
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val scheduleStatus = ScheduleStatus.getCurrentStatus(overrideHours)

    // Real-time GPS Location listener
    var userLatState by remember { mutableStateOf<Double?>(null) }
    var userLngState by remember { mutableStateOf<Double?>(null) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            hasLocationPermission = true
        }
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    DisposableEffect(hasLocationPermission) {
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

        if (hasLocationPermission) {
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

    val hasActiveRequest = activeRequest != null && (activeRequest?.status == RideRequestStatus.PENDING || activeRequest?.status == RideRequestStatus.ACCEPTED)

    val canNotifyDriver = (GeofenceManager.isTestModeEnabled || isNearGate) &&
            scheduleStatus.isAvailable &&
            isDriverAvailable &&
            cooldownSeconds == 0 &&
            !isSendingRequest &&
            !hasActiveRequest

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
        CampusPullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                if (!isRefreshing) {
                    isRefreshing = true
                    scope.launch {
                        try {
                            val result = repository.refreshAllData(UserRole.STUDENT)
                            if (result.isFailure) {
                                snackbarHostState.showSnackbar(
                                    message = "Couldn't refresh. Check your internet connection."
                                )
                            }
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar(
                                message = "Couldn't refresh. Check your internet connection."
                            )
                        } finally {
                            isRefreshing = false
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
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

            // 1. Pickup Zone Status Card (Simple 2 States)
            val isInsideZone = isNearGate
            val transition = updateTransition(targetState = isInsideZone, label = "ZoneTransition")
            val cardBgColor by transition.animateColor(label = "CardBg") { inside ->
                if (inside) Color(0xFFF0FDF4) else Color(0xFFFEF2F2)
            }
            val cardBorderColor by transition.animateColor(label = "CardBorder") { inside ->
                if (inside) Color(0xFF86EFAC) else Color(0xFFFCA5A5)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = cardBgColor),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, cardBorderColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(if (isInsideZone) Color(0xFF22C55E) else Color(0xFFEF4444)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isInsideZone) "✓" else "📍",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isInsideZone) "You're in the pickup zone" else "You're not in the pickup zone",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isInsideZone) Color(0xFF14532D) else Color(0xFF7F1D1D)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isInsideZone) "You can now request a ride." else "Please move to the Gate to request a ride.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isInsideZone) Color(0xFF166534) else Color(0xFF991B1B)
                        )
                    }
                }
            }

            // 2. Dual-Cart Fleet Overview & Dedicated Cart Switcher
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CAMPUS RIDE LIVE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Tap cart to view route",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Cart 1 Card
                CampusCartCard(
                    cartNumber = 1,
                    cartState = cart1State,
                    isSelected = (selectedCartTab == "cart_1"),
                    onSelect = { selectedCartTab = "cart_1" }
                )

                // Cart 2 Card
                CampusCartCard(
                    cartNumber = 2,
                    cartState = cart2State,
                    isSelected = (selectedCartTab == "cart_2"),
                    onSelect = { selectedCartTab = "cart_2" }
                )
            }

            // 3. Dedicated Selected Cart Live Route Status Card with Route Stop Timeline
            LiveRouteTrackingCard(
                cartState = activeCartState,
                isDriverAvailable = activeCartState.isLive
            )

            // Active Ride Request Status Banner
            AnimatedVisibility(
                visible = activeRequest != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                activeRequest?.let { req ->
                    val studentFacingDriverLocation = if (req.driverLat != null && req.driverLng != null) {
                        com.example.location.CampusLandmarkZone.getStudentFacingDriverLocation(req.driverLat, req.driverLng, req.assignedCartId ?: selectedCartTab)
                    } else if (activeCartState.latitude != null && activeCartState.longitude != null) {
                        com.example.location.CampusLandmarkZone.getStudentFacingDriverLocation(activeCartState.latitude, activeCartState.longitude, selectedCartTab)
                    } else {
                        "Driver location updating…"
                    }

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
                                if (req.status == RideRequestStatus.ACCEPTED) {
                                    Text(
                                        text = "🚗 $studentFacingDriverLocation",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1D4ED8)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "En route to ${req.pickupLocationEnum.displayName}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    val isCartOnline = activeCartState.isLive
                                    val cartNameText = req.assignedCartName ?: if (selectedCartTab == "cart_1") "Cart 1" else "Cart 2"
                                    val etaText = if (isCartOnline) "${activeCartState.etaMinutes ?: 2} min" else "Not Available"
                                    Text(
                                        text = "Status: Pending • Cart: $cartNameText • ETA: $etaText",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
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
                        if (!isSendingRequest && canNotifyDriver) {
                            Log.d("CAMPUS_RIDE_TRACE", "NOTIFY_CLICK: Student tapped Notify Driver button")
                            selectedStudentsCount = 1
                            showStudentsWaitingSheet = true
                        }
                    },
                    enabled = canNotifyDriver && !isSendingRequest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("notify_driver_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFE2E8F0),
                        disabledContentColor = Color(0xFF94A3B8)
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.dp)
                ) {
                    Text(
                        text = when {
                            hasActiveRequest -> "Request Pending"
                            cooldownSeconds > 0 -> "Cooldown Active"
                            else -> "Notify Driver"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Premium explanatory message when disabled
                if (!canNotifyDriver && !isSendingRequest) {
                    val disabledReason = when {
                        hasActiveRequest -> "You already have a pending or active ride request."
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

        if (showStudentsWaitingSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    if (!isSendingRequest) {
                        showStudentsWaitingSheet = false
                    }
                },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .width(40.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                    )
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header Icon
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFEE2E2)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = null,
                            tint = Color(0xFFDC2626),
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "How many students are waiting?",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Tell the driver how many people are currently waiting so they can prioritize the request.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // 2-Column Grid Layout (1 to 10)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (row in 0 until 5) {
                            val count1 = row * 2 + 1
                            val count2 = row * 2 + 2
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                WaitingCountOptionCard(
                                    count = count1,
                                    isSelected = (selectedStudentsCount == count1),
                                    enabled = !isSendingRequest,
                                    onClick = { selectedStudentsCount = count1 },
                                    modifier = Modifier.weight(1f)
                                )
                                WaitingCountOptionCard(
                                    count = count2,
                                    isSelected = (selectedStudentsCount == count2),
                                    enabled = !isSendingRequest,
                                    onClick = { selectedStudentsCount = count2 },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Selection Summary Section (Fixed to GATE)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFFEF2F2),
                        border = BorderStroke(1.dp, Color(0xFFFECACA)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFDC2626)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Group,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "$selectedStudentsCount ${if (selectedStudentsCount == 1) "student is" else "students are"} waiting",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF991B1B)
                                )
                                Text(
                                    text = "Pickup Point: ${studentPickupLocation.displayName} (Gate)",
                                    fontSize = 12.sp,
                                    color = Color(0xFFB91C1C)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Primary Action: Confirm & Notify Driver
                    Button(
                        onClick = {
                            if (!isSendingRequest && !hasActiveRequest) {
                                Log.d("CAMPUS_RIDE_TRACE", "NOTIFY_CLICK: User confirmed notification with $selectedStudentsCount student(s)")
                                scope.launch {
                                    isSendingRequest = true
                                    val result = repository.sendStudentRideRequest(
                                        studentLat = activeLat,
                                        studentLng = activeLng,
                                        studentsWaiting = selectedStudentsCount,
                                        pickupLocation = studentPickupLocation,
                                        assignedCartId = selectedCartTab
                                    )
                                    isSendingRequest = false
                                    if (result.isSuccess) {
                                        showStudentsWaitingSheet = false
                                        snackbarHostState.showSnackbar("Driver notified: $selectedStudentsCount student(s) waiting at Gate.")
                                    } else {
                                        val exMsg = result.exceptionOrNull()?.message ?: "Could not notify driver"
                                        snackbarHostState.showSnackbar(exMsg)
                                    }
                                }
                            }
                        },
                        enabled = !isSendingRequest && !hasActiveRequest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("confirm_notify_driver_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFDC2626),
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFFE2E8F0),
                            disabledContentColor = Color(0xFF94A3B8)
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        if (isSendingRequest) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Notifying Driver…",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Confirm & Notify Driver",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Cancel Action
                    OutlinedButton(
                        onClick = {
                            if (!isSendingRequest) {
                                showStudentsWaitingSheet = false
                            }
                        },
                        enabled = !isSendingRequest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("cancel_notify_driver_button"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text(
                            text = "Cancel",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WaitingCountOptionCard(
    count: Int,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPlural = count > 1
    val labelText = if (isPlural) "students" else "student"

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) Color(0xFFFEE2E2) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) Color(0xFFDC2626) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        shadowElevation = if (isSelected) 2.dp else 0.dp,
        modifier = modifier
            .height(64.dp)
            .testTag("waiting_count_card_$count")
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) Color(0xFFDC2626) else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$count",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = labelText,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color(0xFF991B1B) else MaterialTheme.colorScheme.onSurface
                    )
                    if (count >= 5) {
                        Text(
                            text = "Priority",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color(0xFFDC2626) else Color(0xFFEA580C)
                        )
                    }
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = Color(0xFFDC2626),
                    modifier = Modifier.size(18.dp)
                )
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
