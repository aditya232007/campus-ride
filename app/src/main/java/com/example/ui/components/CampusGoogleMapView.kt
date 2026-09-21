package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GolfCartState
import com.example.util.CartPhoneDialer
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import java.util.Locale

/**
 * Official IIIT Bhagalpur Campus Coordinates & Route
 */
object CampusMapConstants {
    val MAIN_GATE = LatLng(25.2531616, 87.0370730)
    val TRUNKET = LatLng(25.2577186, 87.0381730)
    val COMPUTER_CENTRE = LatLng(25.2590500, 87.0394730)
    val ACADEMIC_BLOCK = LatLng(25.260555, 87.039888)
    val HOSTEL = LatLng(25.2577810, 87.0418910)

    val CAMPUS_CENTER = LatLng(25.2575, 87.0392)
    const val DEFAULT_ZOOM = 16.2f

    // Canonical Fixed Polyline for the small bounded campus
    val CAMPUS_ROUTE_POLYLINE = listOf(
        MAIN_GATE,
        TRUNKET,
        COMPUTER_CENTRE,
        ACADEMIC_BLOCK,
        HOSTEL
    )

    val CAMPUS_BOUNDS: LatLngBounds by lazy {
        LatLngBounds.builder()
            .include(MAIN_GATE)
            .include(TRUNKET)
            .include(COMPUTER_CENTRE)
            .include(ACADEMIC_BLOCK)
            .include(HOSTEL)
            .build()
    }
}

enum class MapEngine {
    GOOGLE_MAPS,
    OPEN_STREET_MAP
}

/**
 * Robust, production-grade Campus Map Component.
 *
 * Defaults to Google Maps with real-time coordinate synchronization via Firestore.
 * Supports switching to OpenStreetMap as a free fallback.
 *
 * Supports live tracking of:
 * - Real-time location of available golf carts with smooth animation
 * - Firestore backend coordinate synchronization with live status beacon
 * - Available-only filtering and interactive cart inspection card
 * - Student live GPS location
 * - Fixed campus route & landmark stops
 */
@Composable
fun CampusGoogleMapView(
    cartState: GolfCartState? = null,
    cart1State: GolfCartState? = null,
    cart2State: GolfCartState? = null,
    modifier: Modifier = Modifier,
    studentLatitude: Double? = null,
    studentLongitude: Double? = null,
    isDriverView: Boolean = false,
    initialEngine: MapEngine = MapEngine.GOOGLE_MAPS
) {
    var currentMapEngine by remember { mutableStateOf(initialEngine) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .testTag("campus_map_container")
    ) {
        when (currentMapEngine) {
            MapEngine.GOOGLE_MAPS -> {
                GoogleMapsInternalView(
                    cartState = cartState,
                    cart1State = cart1State,
                    cart2State = cart2State,
                    studentLatitude = studentLatitude,
                    studentLongitude = studentLongitude,
                    isDriverView = isDriverView,
                    onSwitchEngine = { currentMapEngine = MapEngine.OPEN_STREET_MAP },
                    modifier = Modifier.fillMaxSize()
                )
            }
            MapEngine.OPEN_STREET_MAP -> {
                CampusOpenStreetMapView(
                    cartState = cartState,
                    cart1State = cart1State,
                    cart2State = cart2State,
                    studentLatitude = studentLatitude,
                    studentLongitude = studentLongitude,
                    isDriverView = isDriverView,
                    modifier = Modifier.fillMaxSize()
                )
                // Small toggle button to switch back to Google Maps
                Surface(
                    onClick = { currentMapEngine = MapEngine.GOOGLE_MAPS },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = "Switch to Google Maps",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Use Google Maps",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GoogleMapsInternalView(
    cartState: GolfCartState?,
    cart1State: GolfCartState?,
    cart2State: GolfCartState?,
    studentLatitude: Double?,
    studentLongitude: Double?,
    isDriverView: Boolean,
    onSwitchEngine: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Resolve Cart 1 and Cart 2 independently
    val effectiveCart1 = cart1State ?: if (cartState?.cartId == "cart_1") cartState else null
    val effectiveCart2 = cart2State ?: if (cartState?.cartId == "cart_2") cartState else null

    // Determine availability states
    val isC1Outside = effectiveCart1?.isOutsideCampus == true || effectiveCart1?.isInsideCampus == false
    val isC1Available = !isC1Outside && (effectiveCart1?.isAvailable == true) &&
        (effectiveCart1?.isDriverOnline == true || effectiveCart1?.presenceState?.isLocationAvailable == true) &&
        !effectiveCart1.driverStatus.equals("Offline", ignoreCase = true) &&
        !effectiveCart1.driverStatus.equals("Lunch Break", ignoreCase = true)

    val isC2Outside = effectiveCart2?.isOutsideCampus == true || effectiveCart2?.isInsideCampus == false
    val isC2Available = !isC2Outside && (effectiveCart2?.isAvailable == true) &&
        (effectiveCart2?.isDriverOnline == true || effectiveCart2?.presenceState?.isLocationAvailable == true) &&
        !effectiveCart2.driverStatus.equals("Offline", ignoreCase = true) &&
        !effectiveCart2.driverStatus.equals("Lunch Break", ignoreCase = true)

    val availableCartCount = (if (isC1Available) 1 else 0) + (if (isC2Available) 1 else 0)

    // Filter state: show all carts vs available only
    var showAvailableOnly by remember { mutableStateOf(false) }

    // Map type: Normal vs Hybrid (satellite with labels)
    var isSatelliteView by remember { mutableStateOf(false) }

    // Selected cart for interactive bottom inspector card
    var selectedCartDetail by remember { mutableStateOf<GolfCartState?>(null) }

    val cart1LatLng = remember(effectiveCart1?.latitude, effectiveCart1?.longitude) {
        val lat = effectiveCart1?.latitude
        val lng = effectiveCart1?.longitude
        if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
            LatLng(lat, lng)
        } else null
    }

    val cart2LatLng = remember(effectiveCart2?.latitude, effectiveCart2?.longitude) {
        val lat = effectiveCart2?.latitude
        val lng = effectiveCart2?.longitude
        if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
            LatLng(lat, lng)
        } else null
    }

    val studentLatLng = remember(studentLatitude, studentLongitude) {
        if (studentLatitude != null && studentLongitude != null && studentLatitude != 0.0 && studentLongitude != 0.0) {
            LatLng(studentLatitude, studentLongitude)
        } else null
    }

    val cameraPositionState = rememberCameraPositionState {
        val initialCenter = when {
            isC1Available && cart1LatLng != null -> cart1LatLng
            isC2Available && cart2LatLng != null -> cart2LatLng
            cart1LatLng != null -> cart1LatLng
            cart2LatLng != null -> cart2LatLng
            else -> CampusMapConstants.CAMPUS_CENTER
        }
        position = CameraPosition.fromLatLngZoom(initialCenter, CampusMapConstants.DEFAULT_ZOOM)
    }

    var hasCenteredOnCart by remember { mutableStateOf(false) }
    LaunchedEffect(cart1LatLng, cart2LatLng) {
        val primaryTarget = when {
            isC1Available && cart1LatLng != null -> cart1LatLng
            isC2Available && cart2LatLng != null -> cart2LatLng
            cart1LatLng != null -> cart1LatLng
            cart2LatLng != null -> cart2LatLng
            else -> null
        }
        if (primaryTarget != null && !hasCenteredOnCart) {
            hasCenteredOnCart = true
            try {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(primaryTarget, 16.8f),
                    1000
                )
            } catch (_: Exception) {}
        }
    }

    val mapUiSettings = remember {
        MapUiSettings(
            zoomControlsEnabled = false,
            compassEnabled = true,
            myLocationButtonEnabled = false,
            mapToolbarEnabled = false,
            rotationGesturesEnabled = true,
            scrollGesturesEnabled = true,
            tiltGesturesEnabled = false,
            zoomGesturesEnabled = true
        )
    }

    val mapProperties = remember(isSatelliteView) {
        MapProperties(
            isMyLocationEnabled = false,
            mapType = if (isSatelliteView) MapType.HYBRID else MapType.NORMAL
        )
    }

    val gateMarkerIcon = remember { createStopMarkerBitmap(context, "Gate", 0xFF15803D.toInt()) }
    val trunketMarkerIcon = remember { createStopMarkerBitmap(context, "Trunket", 0xFF2563EB.toInt()) }
    val ccMarkerIcon = remember { createStopMarkerBitmap(context, "CC", 0xFF7C3AED.toInt()) }
    val acadMarkerIcon = remember { createStopMarkerBitmap(context, "Acad", 0xFF0D9488.toInt()) }
    val hostelMarkerIcon = remember { createStopMarkerBitmap(context, "Hostel", 0xFFD97706.toInt()) }
    val studentMarkerIcon = remember { createStudentMarkerBitmap(context) }

    val cart1Presence = if (isC1Outside) com.example.data.model.CartPresenceState.OFFLINE else (effectiveCart1?.presenceState ?: com.example.data.model.CartPresenceState.OFFLINE)
    val cart1MarkerIcon = remember(cart1Presence, isC1Outside, isC1Available) {
        createCartMarkerBitmap(context, cartNumber = 1, presence = cart1Presence, isAvailable = isC1Available)
    }

    val cart2Presence = if (isC2Outside) com.example.data.model.CartPresenceState.OFFLINE else (effectiveCart2?.presenceState ?: com.example.data.model.CartPresenceState.OFFLINE)
    val cart2MarkerIcon = remember(cart2Presence, isC2Outside, isC2Available) {
        createCartMarkerBitmap(context, cartNumber = 2, presence = cart2Presence, isAvailable = isC2Available)
    }

    val cart1MarkerState = rememberMarkerState(key = "marker_cart_1")
    val cart2MarkerState = rememberMarkerState(key = "marker_cart_2")

    // Smoothly animate Cart 1 marker position and rotation without jumping
    val cart1Anim = rememberAnimatedCartMarkerState(
        targetLat = cart1LatLng?.latitude,
        targetLng = cart1LatLng?.longitude,
        targetBearing = effectiveCart1?.bearing,
        durationMs = 1200,
        onPositionUpdate = { lat, lng ->
            cart1MarkerState.position = LatLng(lat, lng)
        }
    )

    // Smoothly animate Cart 2 marker position and rotation without jumping
    val cart2Anim = rememberAnimatedCartMarkerState(
        targetLat = cart2LatLng?.latitude,
        targetLng = cart2LatLng?.longitude,
        targetBearing = effectiveCart2?.bearing,
        durationMs = 1200,
        onPositionUpdate = { lat, lng ->
            cart2MarkerState.position = LatLng(lat, lng)
        }
    )

    val shouldShowCart1 = (!showAvailableOnly || isC1Available) && (cart1LatLng != null || cart1Anim.hasValidLocation)
    val shouldShowCart2 = (!showAvailableOnly || isC2Available) && (cart2LatLng != null || cart2Anim.hasValidLocation)

    Box(modifier = modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings = mapUiSettings,
            properties = mapProperties,
            onMapClick = {
                selectedCartDetail = null
            }
        ) {
            // Campus Route Polyline
            Polyline(
                points = CampusMapConstants.CAMPUS_ROUTE_POLYLINE,
                color = Color(0xFF2563EB),
                width = 14f,
                jointType = JointType.ROUND,
                startCap = RoundCap(),
                endCap = RoundCap()
            )
            Polyline(
                points = CampusMapConstants.CAMPUS_ROUTE_POLYLINE,
                color = Color(0xFF93C5FD),
                width = 6f,
                jointType = JointType.ROUND,
                startCap = RoundCap(),
                endCap = RoundCap()
            )

            // Campus Stops
            Marker(
                state = MarkerState(position = CampusMapConstants.MAIN_GATE),
                title = "Main Gate",
                snippet = "Student Pickup Point (70m Geofence)",
                icon = gateMarkerIcon,
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
            )
            Marker(
                state = MarkerState(position = CampusMapConstants.TRUNKET),
                title = "Trunket",
                snippet = "Campus Route Stop",
                icon = trunketMarkerIcon,
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
            )
            Marker(
                state = MarkerState(position = CampusMapConstants.COMPUTER_CENTRE),
                title = "Computer Centre",
                snippet = "Academic Hub Stop",
                icon = ccMarkerIcon,
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
            )
            Marker(
                state = MarkerState(position = CampusMapConstants.ACADEMIC_BLOCK),
                title = "Academic Block",
                snippet = "Lecture Halls & Labs",
                icon = acadMarkerIcon,
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
            )
            Marker(
                state = MarkerState(position = CampusMapConstants.HOSTEL),
                title = "Hostel",
                snippet = "Boys Hostel Stop",
                icon = hostelMarkerIcon,
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
            )

            // Student Marker
            if (studentLatLng != null) {
                Marker(
                    state = MarkerState(position = studentLatLng),
                    title = "My Location",
                    snippet = "Student Location",
                    icon = studentMarkerIcon,
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f)
                )
            }

            // Radar Pulse Ring for Available Carts to highlight live GPS sync from Firestore
            if (shouldShowCart1 && isC1Available) {
                val currentPos = cart1MarkerState.position
                Circle(
                    center = currentPos,
                    radius = 24.0,
                    fillColor = Color(0x3316A34A),
                    strokeColor = Color(0x9916A34A),
                    strokeWidth = 3f
                )
            }

            if (shouldShowCart2 && isC2Available) {
                val currentPos = cart2MarkerState.position
                Circle(
                    center = currentPos,
                    radius = 24.0,
                    fillColor = Color(0x332563EB),
                    strokeColor = Color(0x992563EB),
                    strokeWidth = 3f
                )
            }

            // Real-Time Cart 1 Marker (Synchronized via Firestore drivers/cart_1)
            if (shouldShowCart1) {
                val titleText = if (isC1Available) "CART 1 • AVAILABLE" else if (isC1Outside) "CART 1 (Outside Campus)" else "CART 1 (Busy)"
                val latFormatted = String.format(Locale.US, "%.5f", cart1MarkerState.position.latitude)
                val lngFormatted = String.format(Locale.US, "%.5f", cart1MarkerState.position.longitude)
                val snippetText = "GPS: $latFormatted, $lngFormatted • ${effectiveCart1?.speedKmH ?: 0} km/h • Stop: ${effectiveCart1?.currentStop ?: "In Transit"}"

                Marker(
                    state = cart1MarkerState,
                    title = titleText,
                    snippet = snippetText,
                    icon = cart1MarkerIcon,
                    rotation = cart1Anim.bearing,
                    flat = true,
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 1.0f),
                    onClick = {
                        selectedCartDetail = effectiveCart1 ?: cartState
                        false
                    }
                )
            }

            // Real-Time Cart 2 Marker (Synchronized via Firestore drivers/cart_2)
            if (shouldShowCart2) {
                val titleText = if (isC2Available) "CART 2 • AVAILABLE" else if (isC2Outside) "CART 2 (Outside Campus)" else "CART 2 (Busy)"
                val latFormatted = String.format(Locale.US, "%.5f", cart2MarkerState.position.latitude)
                val lngFormatted = String.format(Locale.US, "%.5f", cart2MarkerState.position.longitude)
                val snippetText = "GPS: $latFormatted, $lngFormatted • ${effectiveCart2?.speedKmH ?: 0} km/h • Stop: ${effectiveCart2?.currentStop ?: "In Transit"}"

                Marker(
                    state = cart2MarkerState,
                    title = titleText,
                    snippet = snippetText,
                    icon = cart2MarkerIcon,
                    rotation = cart2Anim.bearing,
                    flat = true,
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 1.0f),
                    onClick = {
                        selectedCartDetail = effectiveCart2 ?: cartState
                        false
                    }
                )
            }
        }

        // Top Status & Controls Overlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Left Status Badges: Firestore Live Sync & Available Count
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Firestore Live Sync Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF0F172A).copy(alpha = 0.88f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22C55E))
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Firestore Live",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF86EFAC)
                        )
                    }
                }

                // Available Carts Count Pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (availableCartCount > 0) Color(0xFF14532D).copy(alpha = 0.9f) else Color(0xFF334155).copy(alpha = 0.9f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (availableCartCount > 0) Color(0xFF4ADE80) else Color(0xFF94A3B8))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (availableCartCount > 0) Color(0xFF4ADE80) else Color(0xFFCBD5E1),
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$availableCartCount Available",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            // Right Map Control Action Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Filter: Available Only toggle
                FilledTonalIconButton(
                    onClick = { showAvailableOnly = !showAvailableOnly },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (showAvailableOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        contentColor = if (showAvailableOnly) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = "Toggle Available Only Filter",
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Satellite / Normal Toggle
                FilledTonalIconButton(
                    onClick = { isSatelliteView = !isSatelliteView },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isSatelliteView) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        contentColor = if (isSatelliteView) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = "Toggle Satellite / Normal Layer",
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Switch Engine to OpenStreetMap
                FilledTonalIconButton(
                    onClick = onSwitchEngine,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = "Switch to OpenStreetMap",
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Center on active cart FAB
                val centerTarget = when {
                    isC1Available && cart1LatLng != null -> cart1LatLng
                    isC2Available && cart2LatLng != null -> cart2LatLng
                    cart1LatLng != null -> cart1LatLng
                    cart2LatLng != null -> cart2LatLng
                    else -> null
                }
                if (centerTarget != null) {
                    SmallFloatingActionButton(
                        onClick = {
                            try {
                                cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(centerTarget, 17.5f))
                            } catch (_: Exception) {}
                        },
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        contentColor = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = "Recenter on Active Cart",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Fit campus bounds FAB
                SmallFloatingActionButton(
                    onClick = {
                        try {
                            cameraPositionState.move(
                                CameraUpdateFactory.newLatLngBounds(CampusMapConstants.CAMPUS_BOUNDS, 60)
                            )
                        } catch (_: Exception) {
                            try {
                                cameraPositionState.move(
                                    CameraUpdateFactory.newLatLngZoom(CampusMapConstants.CAMPUS_CENTER, CampusMapConstants.DEFAULT_ZOOM)
                                )
                            } catch (_: Exception) {}
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = CircleShape,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CropFree,
                        contentDescription = "Fit Campus Bounds",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Bottom Strip: Cart 1 & Cart 2 Quick Selection & Live Status Pills
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cart 1 Indicator Pill
            Surface(
                onClick = {
                    if (cart1LatLng != null) {
                        try {
                            cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(cart1LatLng, 17.5f))
                        } catch (_: Exception) {}
                    }
                    selectedCartDetail = effectiveCart1 ?: cartState
                },
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.9f),
                border = androidx.compose.foundation.BorderStroke(1.2.dp, if (isC1Available) Color(0xFF22C55E) else Color(0xFF64748B))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isC1Available) Color(0xFF22C55E) else if (cart1Presence.isLocationAvailable) Color(0xFF3B82F6) else Color(0xFF94A3B8))
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Cart 1: ${if (isC1Available) "Available" else cart1Presence.badgeText}",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // Cart 2 Indicator Pill
            Surface(
                onClick = {
                    if (cart2LatLng != null) {
                        try {
                            cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(cart2LatLng, 17.5f))
                        } catch (_: Exception) {}
                    }
                    selectedCartDetail = effectiveCart2 ?: cartState
                },
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.9f),
                border = androidx.compose.foundation.BorderStroke(1.2.dp, if (isC2Available) Color(0xFF22C55E) else Color(0xFF2563EB))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isC2Available) Color(0xFF22C55E) else if (cart2Presence.isLocationAvailable) Color(0xFF3B82F6) else Color(0xFF94A3B8))
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Cart 2: ${if (isC2Available) "Available" else cart2Presence.badgeText}",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // Interactive Bottom Inspection Card for the Tapped Cart
        AnimatedVisibility(
            visible = selectedCartDetail != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val inspectedCart = selectedCartDetail
            if (inspectedCart != null) {
                val isCart1 = inspectedCart.cartId == "cart_1"
                val isInspectedAvailable = if (isCart1) isC1Available else isC2Available
                val cartLat = inspectedCart.latitude
                val cartLng = inspectedCart.longitude

                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        // Header row with Cart Title, Status Badge, and Close Button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isCart1) Color(0xFF15803D) else Color(0xFF1D4ED8),
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = if (isCart1) "1" else "2",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = Color.White
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = if (isCart1) "Cart 1 (Shivam)" else "Cart 2 (Kartik)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Synced via Firestore 'drivers/${inspectedCart.cartId}'",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isInspectedAvailable) Color(0xFFDCFCE7) else Color(0xFFE2E8F0)
                                ) {
                                    Text(
                                        text = if (isInspectedAvailable) "AVAILABLE" else (inspectedCart.driverStatus ?: "OFFLINE"),
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 10.sp,
                                        color = if (isInspectedAvailable) Color(0xFF15803D) else Color(0xFF475569),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                IconButton(
                                    onClick = { selectedCartDetail = null },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Inspector",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Real-time Coordinate & Telemetry Grid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "GPS COORDINATES",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = if (cartLat != null && cartLng != null) String.format(Locale.US, "%.5f, %.5f", cartLat, cartLng) else "Waiting for GPS...",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "SPEED & STOP",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = "${inspectedCart.speedKmH} km/h • ${inspectedCart.currentStop}",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Action Buttons: Center on Map & Call Driver
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (cartLat != null && cartLng != null) {
                                        try {
                                            cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(LatLng(cartLat, cartLng), 18f))
                                        } catch (_: Exception) {}
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MyLocation,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Center View", fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    CartPhoneDialer.dialCart(context, inspectedCart.cartId)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isCart1) Color(0xFF15803D) else Color(0xFF1D4ED8)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhoneInTalk,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Call Driver", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun createStopMarkerBitmap(context: Context, label: String, colorInt: Int): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val sizePx = (30 * density).toInt().coerceAtLeast(36)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt
        style = Paint.Style.FILL
    }
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 2f, paint)

    val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 5f, whitePaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 9f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    val yPos = (sizePx / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
    canvas.drawText(label.take(2).uppercase(), sizePx / 2f, yPos, textPaint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun createStudentMarkerBitmap(context: Context): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val sizePx = (28 * density).toInt().coerceAtLeast(32)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(70, 37, 99, 235)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 1f, glowPaint)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(37, 99, 235)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 4f, fillPaint)

    val whiteBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 4f, whiteBorder)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun createCartMarkerBitmap(
    context: Context,
    cartNumber: Int,
    presence: com.example.data.model.CartPresenceState = com.example.data.model.CartPresenceState.ONLINE_LOCATION_AVAILABLE,
    isAvailable: Boolean = true
): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val widthPx = (130 * density).toInt().coerceAtLeast(140)
    val heightPx = (54 * density).toInt().coerceAtLeast(60)
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val isFresh = presence == com.example.data.model.CartPresenceState.ONLINE_LOCATION_AVAILABLE
    val isStale = presence == com.example.data.model.CartPresenceState.ONLINE_LOCATION_STALE

    // Visual theme: Available = Vibrant Green, Cart 2 = Vibrant Blue, Busy = Amber/Blue, Offline = Slate
    val baseColor = when {
        !isFresh && !isStale -> android.graphics.Color.rgb(100, 116, 139) // Slate Gray for Offline
        isStale -> android.graphics.Color.rgb(217, 119, 6) // Amber for Stale
        isAvailable -> if (cartNumber == 1) android.graphics.Color.rgb(21, 128, 61) else android.graphics.Color.rgb(29, 78, 216)
        else -> android.graphics.Color.rgb(30, 41, 59) // Deep Navy for In-Trip
    }

    val pillHeight = heightPx - (8 * density)
    val pillRect = android.graphics.RectF(2f * density, 2f * density, widthPx - (2f * density), pillHeight)
    val cornerRadius = 14f * density

    // Drop shadow
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(70, 0, 0, 0)
        style = Paint.Style.FILL
    }
    canvas.drawRoundRect(
        android.graphics.RectF(pillRect.left + 1f, pillRect.top + 2.5f, pillRect.right + 1f, pillRect.bottom + 2.5f),
        cornerRadius,
        cornerRadius,
        shadowPaint
    )

    // Pill background
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = baseColor
        style = Paint.Style.FILL
    }
    canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, bgPaint)

    // Crisp white or emerald border
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isAvailable && isFresh) android.graphics.Color.rgb(134, 239, 172) else android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
    }
    canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, borderPaint)

    // Bottom pointer / beak pointing to exact GPS coordinate
    val pointerPath = android.graphics.Path().apply {
        val midX = widthPx / 2f
        moveTo(midX - (8f * density), pillHeight - (1f * density))
        lineTo(midX, heightPx.toFloat() - 1f)
        lineTo(midX + (8f * density), pillHeight - (1f * density))
        close()
    }
    canvas.drawPath(pointerPath, bgPaint)
    canvas.drawPath(pointerPath, borderPaint)

    // White circular badge on the left with prominent cart number
    val badgeRadius = 12f * density
    val badgeCenterX = 16f * density
    val badgeCenterY = pillHeight / 2f

    val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(badgeCenterX, badgeCenterY, badgeRadius, badgePaint)

    val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = baseColor
        textSize = 13f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    val badgeTextY = badgeCenterY - ((badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f)
    canvas.drawText("$cartNumber", badgeCenterX, badgeTextY, badgeTextPaint)

    // Top Label: "CART 1" or "CART 2"
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 11.5f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }
    val labelText = "CART $cartNumber"
    val textY = (pillHeight * 0.44f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
    canvas.drawText(labelText, 33f * density, textY, textPaint)

    // Sub-Label: "AVAILABLE" / "ON TRIP" / "OFFLINE"
    val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isAvailable && isFresh) android.graphics.Color.rgb(134, 239, 172) else android.graphics.Color.rgb(203, 213, 225)
        textSize = 8.5f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }
    val subLabelText = when {
        !isFresh && !isStale -> "OFFLINE"
        isAvailable -> "AVAILABLE"
        else -> "ON TRIP"
    }
    val subTextY = (pillHeight * 0.76f) - ((subTextPaint.descent() + subTextPaint.ascent()) / 2f)
    canvas.drawText(subLabelText, 33f * density, subTextY, subTextPaint)

    // Live status dot (Bright Lime Green / Amber / Slate)
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isAvailable && isFresh) android.graphics.Color.rgb(74, 222, 128) else if (isFresh) android.graphics.Color.rgb(96, 165, 250) else android.graphics.Color.rgb(203, 213, 225)
        style = Paint.Style.FILL
    }
    val dotRadius = 4f * density
    val dotX = widthPx - (12f * density)
    val dotY = pillHeight / 2f
    canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

