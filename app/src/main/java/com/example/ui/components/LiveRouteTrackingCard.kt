package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.GolfCartState
import com.example.data.model.GolfCartStatus
import com.example.location.CampusLandmarkZone
import com.example.location.CampusLandmarkZone.Companion.RoutePositionResult
import com.example.location.CampusLandmarkZone.Companion.StopVisualState
import java.util.Locale

/**
 * Production Live Campus Cart Tracking Card matching the exact specification:
 * 1. Title & Live Status Indicator (LIVE, STALE, OFFLINE)
 * 2. Embedded Google Map with fixed campus polyline and real-time validated cart GPS
 * 3. Cart Header (Cart 1 / Cart 2) with direct dial action
 * 4. Stop Progress (Main Gate ✓, Trunket ✓, Computer Centre →, Hostel)
 * 5. Current route status & relative time (e.g. "Updated 3 sec ago")
 * 6. Driver GPS telemetry (Accuracy, Coordinates, Speed) when isDriverView = true
 */
@Composable
fun LiveRouteTrackingCard(
    cartState: GolfCartState?,
    cart1State: GolfCartState? = null,
    cart2State: GolfCartState? = null,
    isDriverAvailable: Boolean,
    facultySelectedLocation: String? = null,
    studentLatitude: Double? = null,
    studentLongitude: Double? = null,
    isDriverView: Boolean = false,
    modifier: Modifier = Modifier
) {
    val effectiveCart1 = cart1State ?: if (cartState?.cartId == "cart_1") cartState else null
    val effectiveCart2 = cart2State ?: if (cartState?.cartId == "cart_2") cartState else null
    val context = LocalContext.current

    var selectedCartId by remember {
        mutableStateOf(
            cartState?.cartId ?: if (effectiveCart2?.isLive == true && effectiveCart1?.isLive != true) "cart_2" else "cart_1"
        )
    }

    LaunchedEffect(cartState?.cartId) {
        if (cartState?.cartId != null) {
            selectedCartId = cartState.cartId
        }
    }

    val displayCart = when (selectedCartId) {
        "cart_2" -> effectiveCart2 ?: cartState
        else -> effectiveCart1 ?: cartState
    }

    val cart1Presence = effectiveCart1?.presenceState ?: com.example.data.model.CartPresenceState.OFFLINE
    val cart2Presence = effectiveCart2?.presenceState ?: com.example.data.model.CartPresenceState.OFFLINE
    val activePresence = displayCart?.presenceState ?: com.example.data.model.CartPresenceState.OFFLINE

    val liveCartCount = (if (cart1Presence.isLocationAvailable) 1 else 0) + (if (cart2Presence.isLocationAvailable) 1 else 0)
    var isFullscreenMapOpen by remember { mutableStateOf(false) }

    val isCartOutside = displayCart?.isOutsideCampus == true || displayCart?.isInsideCampus == false
    val isCartOnline = !isCartOutside && (displayCart?.isDriverOnline == true || isDriverAvailable)
    val isCartOffline = isCartOutside || !isCartOnline || activePresence == com.example.data.model.CartPresenceState.OFFLINE
    val isLocationFresh = !isCartOutside && activePresence == com.example.data.model.CartPresenceState.ONLINE_LOCATION_AVAILABLE
    val isLocationStale = !isCartOutside && activePresence == com.example.data.model.CartPresenceState.ONLINE_LOCATION_STALE
    val isNoLocationYet = !isCartOutside && isCartOnline && activePresence == com.example.data.model.CartPresenceState.ONLINE_NO_LOCATION

    val routeResult: RoutePositionResult? = if (!isCartOutside && isCartOnline && displayCart?.hasCoordinates == true && !displayCart.isLocationExpiredOrMissing) {
        CampusLandmarkZone.evaluateRoutePosition(
            latitude = displayCart.latitude,
            longitude = displayCart.longitude,
            bearing = displayCart.bearing,
            relativeMovement = displayCart.relativeMovement,
            speedKmH = displayCart.speedKmH ?: 0,
            accuracy = displayCart.accuracy ?: 0f,
            timestamp = displayCart.locationTimestampMillis ?: displayCart.lastUpdatedMillis ?: System.currentTimeMillis(),
            cartId = displayCart.cartId ?: "cart_1"
        )
    } else null

    // Formatted Relative Time
    val timeAgoText = remember(displayCart?.lastUpdatedMillis, displayCart?.locationTimestampMillis, isCartOffline, isLocationFresh, isLocationStale, isNoLocationYet) {
        if (isCartOffline) {
            ""
        } else if (isNoLocationYet) {
            "Location updating..."
        } else {
            val ts = displayCart?.locationTimestampMillis ?: displayCart?.lastUpdatedMillis ?: 0L
            if (ts == 0L) "" else {
                val seconds = ((System.currentTimeMillis() - ts) / 1000L).coerceAtLeast(1)
                when {
                    seconds < 60 -> "Updated $seconds sec ago"
                    else -> "Updated ${seconds / 60}m ago"
                }
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_route_tracking_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            // 1. Header: LIVE CART TRACKING + Status Badge (LIVE / STALE / OFFLINE)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "LIVE CART TRACKING",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when {
                        liveCartCount >= 2 -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFDCFCE7),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF86EFAC))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF16A34A))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "2 CARTS LIVE",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF15803D),
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                        liveCartCount == 1 -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFDCFCE7),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF86EFAC))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF16A34A))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "1 CART LIVE",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF15803D),
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                        isLocationFresh -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFDCFCE7),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF86EFAC))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF16A34A))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "LIVE",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF15803D),
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                        isLocationStale -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFFEF3C7),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCD34D))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFD97706))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "STALE",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFFB45309),
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                        isNoLocationYet -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFE0F2FE),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF7DD3FC))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF0284C7))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "ONLINE",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF0369A1),
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                        else -> {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFFF1F5F9),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCBD5E1))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF94A3B8))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "OFFLINE",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF64748B)
                                    )
                                }
                            }
                        }
                    }

                    // Expand Map Button
                    IconButton(
                        onClick = { isFullscreenMapOpen = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Expand Fullscreen Google Map",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Embedded Free OpenStreetMap / Google Map Component (Renders BOTH Carts!)
            CampusGoogleMapView(
                cartState = displayCart,
                cart1State = effectiveCart1,
                cart2State = effectiveCart2,
                studentLatitude = studentLatitude,
                studentLongitude = studentLongitude,
                isDriverView = isDriverView,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            )

            // Fullscreen Map Dialog
            if (isFullscreenMapOpen) {
                Dialog(
                    onDismissRequest = { isFullscreenMapOpen = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        CampusGoogleMapView(
                            cartState = displayCart,
                            cart1State = effectiveCart1,
                            cart2State = effectiveCart2,
                            studentLatitude = studentLatitude,
                            studentLongitude = studentLongitude,
                            isDriverView = isDriverView,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Close Button in Top-Left
                        Surface(
                            onClick = { isFullscreenMapOpen = false },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 16.dp, top = 40.dp)
                                .size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Fullscreen Map",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Cart Selector Pills (When multiple carts are present)
            if (effectiveCart1 != null && effectiveCart2 != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isC1Selected = selectedCartId == "cart_1"
                    Surface(
                        onClick = { selectedCartId = "cart_1" },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isC1Selected) Color(0xFFDCFCE7) else Color(0xFFF1F5F9),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isC1Selected) Color(0xFF16A34A) else Color(0xFFCBD5E1)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (cart1Presence.isLocationAvailable) Color(0xFF16A34A) else Color(0xFF94A3B8))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Cart 1 (${cart1Presence.badgeText})",
                                fontSize = 12.sp,
                                fontWeight = if (isC1Selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isC1Selected) Color(0xFF15803D) else Color(0xFF64748B)
                            )
                        }
                    }

                    val isC2Selected = selectedCartId == "cart_2"
                    Surface(
                        onClick = { selectedCartId = "cart_2" },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isC2Selected) Color(0xFFEFF6FF) else Color(0xFFF1F5F9),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isC2Selected) Color(0xFF2563EB) else Color(0xFFCBD5E1)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (cart2Presence.isLocationAvailable) Color(0xFF2563EB) else Color(0xFF94A3B8))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Cart 2 (${cart2Presence.badgeText})",
                                fontSize = 12.sp,
                                fontWeight = if (isC2Selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isC2Selected) Color(0xFF1D4ED8) else Color(0xFF64748B)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 3. Cart Header Label with Concise Location/Status
            val currentCartNumber = if (displayCart?.cartId == "cart_2" || selectedCartId == "cart_2") 2 else 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = displayCart?.displayCartLabel ?: "Cart $currentCartNumber",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val statusText = when {
                    isCartOutside -> "Driver Not Available"
                    isCartOffline -> "Offline"
                    routeResult?.isAtLandmark == true -> "At ${routeResult.primaryLandmark.displayName}"
                    routeResult != null -> "En route to ${routeResult.nextStopName}"
                    else -> "En Route"
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isCartOutside) Color(0xFFFEF2F2) else if (isCartOffline) Color(0xFFF1F5F9) else if (routeResult?.isAtLandmark == true) Color(0xFFDCFCE7) else Color(0xFFEFF6FF)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCartOutside) Color(0xFFDC2626) else if (isCartOffline) Color(0xFF64748B) else if (routeResult?.isAtLandmark == true) Color(0xFF15803D) else Color(0xFF1D4ED8),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4. Clean Stop Progress List
            CampusStopList(
                routeResult = routeResult,
                isLive = isLocationFresh
            )
        }
    }
}

/**
 * Clean Stop Progress List for the 4 canonical stops:
 * Main Gate, Trunket, Computer Centre, Hostel.
 * Shows ✓ for passed stops, → for next stop, and 📍 for current stop.
 */
@Composable
private fun CampusStopList(
    routeResult: RoutePositionResult?,
    isLive: Boolean
) {
    val stops = CampusLandmarkZone.ROUTE_SEQUENCE

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        stops.forEach { stop ->
            val stopInfo = routeResult?.timelineStops?.find { it.landmark == stop }
            val visualState = if (isLive && stopInfo != null) stopInfo.visualState else StopVisualState.FUTURE_STOP
            val isAtThisStop = isLive && stopInfo?.isAtThisStop == true

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Marker indicator
                    val indicator = when {
                        isAtThisStop -> "📍"
                        visualState == StopVisualState.COMPLETED -> "✓"
                        visualState == StopVisualState.NEXT_STOP -> "→"
                        else -> "•"
                    }

                    Text(
                        text = indicator,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isAtThisStop -> Color(0xFF15803D)
                            visualState == StopVisualState.COMPLETED -> Color(0xFF16A34A)
                            visualState == StopVisualState.NEXT_STOP -> Color(0xFF2563EB)
                            else -> Color(0xFF94A3B8)
                        },
                        modifier = Modifier.width(22.dp)
                    )

                    Text(
                        text = stop.displayName,
                        fontSize = 14.sp,
                        fontWeight = if (isAtThisStop || visualState == StopVisualState.NEXT_STOP) FontWeight.Bold else FontWeight.Medium,
                        color = when {
                            isAtThisStop -> Color(0xFF15803D)
                            visualState == StopVisualState.NEXT_STOP -> Color(0xFF1D4ED8)
                            visualState == StopVisualState.COMPLETED -> Color(0xFF1E293B)
                            else -> Color(0xFF64748B)
                        }
                    )
                }

                // Stop label
                if (isAtThisStop) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFDCFCE7),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = "At Stop",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF15803D),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                } else if (visualState == StopVisualState.NEXT_STOP) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFDBEAFE),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = "Next Stop",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1D4ED8),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
