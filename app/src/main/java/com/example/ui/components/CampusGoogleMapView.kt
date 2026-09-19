package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Phone
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
    OPEN_STREET_MAP,
    GOOGLE_MAPS
}

/**
 * Robust, production-grade Campus Map Component.
 *
 * Defaults to free, open-source OpenStreetMap so that NO paid Google Cloud billing
 * account or Maps API key is required to open, visualize, or track carts.
 *
 * Supports live tracking of:
 * - Golf Cart live real-time GPS coordinates (never snapped)
 * - Student live GPS location (when permission is granted)
 * - Fixed campus route & stops (Main Gate, Trunket, Computer Centre, Hostel)
 */
@Composable
fun CampusGoogleMapView(
    cartState: GolfCartState? = null,
    cart1State: GolfCartState? = null,
    cart2State: GolfCartState? = null,
    modifier: Modifier = Modifier,
    studentLatitude: Double? = null,
    studentLongitude: Double? = null,
    isDriverView: Boolean = false
) {
    // Default to OpenStreetMap to guarantee 100% free usage without paid billing or API keys
    var currentMapEngine by remember { mutableStateOf(MapEngine.OPEN_STREET_MAP) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(RoundedCornerShape(16.dp))
            .testTag("campus_map_container")
    ) {
        when (currentMapEngine) {
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
            }
            MapEngine.GOOGLE_MAPS -> {
                GoogleMapsInternalView(
                    cartState = cartState,
                    cart1State = cart1State,
                    cart2State = cart2State,
                    studentLatitude = studentLatitude,
                    studentLongitude = studentLongitude,
                    isDriverView = isDriverView,
                    modifier = Modifier.fillMaxSize()
                )
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Resolve Cart 1 and Cart 2 independently
    val effectiveCart1 = cart1State ?: if (cartState?.cartId == "cart_1") cartState else null
    val effectiveCart2 = cart2State ?: if (cartState?.cartId == "cart_2") cartState else null

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
        position = CameraPosition.fromLatLngZoom(
            cart1LatLng ?: cart2LatLng ?: CampusMapConstants.CAMPUS_CENTER,
            CampusMapConstants.DEFAULT_ZOOM
        )
    }

    var hasCenteredOnCart by remember { mutableStateOf(false) }
    LaunchedEffect(cart1LatLng, cart2LatLng) {
        val primaryTarget = cart1LatLng ?: cart2LatLng
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

    val mapProperties = remember {
        MapProperties(
            isMyLocationEnabled = false,
            mapType = MapType.NORMAL
        )
    }

    val gateMarkerIcon = remember { createStopMarkerBitmap(context, "Gate", 0xFF15803D.toInt()) }
    val trunketMarkerIcon = remember { createStopMarkerBitmap(context, "Trunket", 0xFF2563EB.toInt()) }
    val ccMarkerIcon = remember { createStopMarkerBitmap(context, "CC", 0xFF7C3AED.toInt()) }
    val acadMarkerIcon = remember { createStopMarkerBitmap(context, "Acad", 0xFF0D9488.toInt()) }
    val hostelMarkerIcon = remember { createStopMarkerBitmap(context, "Hostel", 0xFFD97706.toInt()) }
    val studentMarkerIcon = remember { createStudentMarkerBitmap(context) }

    val isC1Outside = effectiveCart1?.isOutsideCampus == true || effectiveCart1?.isInsideCampus == false
    val cart1Presence = if (isC1Outside) com.example.data.model.CartPresenceState.OFFLINE else (effectiveCart1?.presenceState ?: com.example.data.model.CartPresenceState.OFFLINE)
    val cart1MarkerIcon = remember(cart1Presence, isC1Outside) {
        createCartMarkerBitmap(context, cartNumber = 1, presence = cart1Presence)
    }

    val isC2Outside = effectiveCart2?.isOutsideCampus == true || effectiveCart2?.isInsideCampus == false
    val cart2Presence = if (isC2Outside) com.example.data.model.CartPresenceState.OFFLINE else (effectiveCart2?.presenceState ?: com.example.data.model.CartPresenceState.OFFLINE)
    val cart2MarkerIcon = remember(cart2Presence, isC2Outside) {
        createCartMarkerBitmap(context, cartNumber = 2, presence = cart2Presence)
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

    Box(modifier = modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            uiSettings = mapUiSettings,
            properties = mapProperties
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

            // Real-Time Cart 1 Marker (EXACT GPS, Clearly Labeled "CART 1")
            if (cart1LatLng != null || cart1Anim.hasValidLocation) {
                Marker(
                    state = cart1MarkerState,
                    title = if (isC1Outside) "CART 1 (Driver Not Available)" else "CART 1 (${cart1Presence.badgeText})",
                    snippet = "${effectiveCart1?.currentStop ?: "In Transit"} • Tap to Call Cart 1",
                    icon = cart1MarkerIcon,
                    rotation = cart1Anim.bearing,
                    flat = true,
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 1.0f),
                    onInfoWindowClick = {
                        CartPhoneDialer.dialCart(context, 1)
                    }
                )
            }

            // Real-Time Cart 2 Marker (EXACT GPS, Clearly Labeled "CART 2")
            if (cart2LatLng != null || cart2Anim.hasValidLocation) {
                Marker(
                    state = cart2MarkerState,
                    title = if (isC2Outside) "CART 2 (Driver Not Available)" else "CART 2 (${cart2Presence.badgeText})",
                    snippet = "${effectiveCart2?.currentStop ?: "In Transit"} • Tap to Call Cart 2",
                    icon = cart2MarkerIcon,
                    rotation = cart2Anim.bearing,
                    flat = true,
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 1.0f),
                    onInfoWindowClick = {
                        CartPhoneDialer.dialCart(context, 2)
                    }
                )
            }
        }

        // Overlay Buttons
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val centerTarget = cart1LatLng ?: cart2LatLng
            if (centerTarget != null) {
                SmallFloatingActionButton(
                    onClick = {
                        try {
                            cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(centerTarget, 17f))
                        } catch (_: Exception) {}
                    },
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Recenter on Active Cart",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

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
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CropFree,
                    contentDescription = "Fit Campus Bounds",
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Bottom Two-Cart Status & Quick Pan Strip Overlay
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, bottom = 10.dp),
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
                },
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.88f),
                border = androidx.compose.foundation.BorderStroke(1.2.dp, Color(0xFF16A34A))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (cart1Presence.isLocationAvailable) Color(0xFF22C55E) else Color(0xFF94A3B8))
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Cart 1: ${cart1Presence.badgeText}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = { CartPhoneDialer.dialCart(context, 1) },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "Call Cart 1",
                            tint = Color(0xFF4ADE80),
                            modifier = Modifier.size(13.dp)
                        )
                    }
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
                },
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.88f),
                border = androidx.compose.foundation.BorderStroke(1.2.dp, Color(0xFF2563EB))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (cart2Presence.isLocationAvailable) Color(0xFF3B82F6) else Color(0xFF94A3B8))
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Cart 2: ${cart2Presence.badgeText}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = { CartPhoneDialer.dialCart(context, 2) },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "Call Cart 2",
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(13.dp)
                        )
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
    presence: com.example.data.model.CartPresenceState = com.example.data.model.CartPresenceState.ONLINE_LOCATION_AVAILABLE
): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val widthPx = (116 * density).toInt().coerceAtLeast(124)
    val heightPx = (50 * density).toInt().coerceAtLeast(58)
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val isFresh = presence == com.example.data.model.CartPresenceState.ONLINE_LOCATION_AVAILABLE
    val isStale = presence == com.example.data.model.CartPresenceState.ONLINE_LOCATION_STALE

    // Visual theme: Cart 1 = Vibrant Emerald Green, Cart 2 = Vibrant Royal Blue
    val baseColor = when {
        !isFresh && !isStale -> android.graphics.Color.rgb(100, 116, 139) // Slate Gray for Offline
        isStale -> android.graphics.Color.rgb(217, 119, 6) // Amber for Stale
        cartNumber == 1 -> android.graphics.Color.rgb(21, 128, 61) // Emerald Green for Cart 1 (#15803D)
        else -> android.graphics.Color.rgb(29, 78, 216) // Royal Blue for Cart 2 (#1D4ED8)
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

    // Crisp white border
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
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

    // Bold, unambiguous label: "CART 1" or "CART 2"
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 12f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }
    val labelText = "CART $cartNumber"
    val textY = (pillHeight / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
    canvas.drawText(labelText, 33f * density, textY, textPaint)

    // Live status dot (Bright Lime Green / Amber / Slate)
    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isFresh) android.graphics.Color.rgb(74, 222, 128) else if (isStale) android.graphics.Color.rgb(251, 191, 36) else android.graphics.Color.rgb(203, 213, 225)
        style = Paint.Style.FILL
    }
    val dotRadius = 4f * density
    val dotX = widthPx - (14f * density)
    val dotY = pillHeight / 2f
    canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
