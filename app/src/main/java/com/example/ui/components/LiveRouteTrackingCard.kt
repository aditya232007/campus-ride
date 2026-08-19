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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GolfCartState
import com.example.data.model.GolfCartStatus
import com.example.location.CampusLandmarkZone
import com.example.location.CampusLandmarkZone.Companion.RoutePositionResult
import com.example.location.CampusLandmarkZone.Companion.StopVisualState

/**
 * Vertical "Where Is My Train" style Live Campus Cart Route Tracking Card.
 *
 * Visualizing the canonical fixed route:
 * MAIN GATE <---> TRUNKUT <---> COMPUTER CENTRE <---> HOSTEL
 */
@Composable
fun LiveRouteTrackingCard(
    cartState: GolfCartState?,
    isDriverAvailable: Boolean,
    facultySelectedLocation: String? = null,
    modifier: Modifier = Modifier
) {
    val isCartOnline = cartState != null &&
            cartState.status != GolfCartStatus.OFFLINE &&
            isDriverAvailable

    val lastUpdateAgeMs = cartState?.lastUpdatedMillis?.let { System.currentTimeMillis() - it } ?: Long.MAX_VALUE
    val isLocationFresh = isCartOnline && cartState?.latitude != null && cartState.longitude != null && lastUpdateAgeMs < 45_000

    val routeResult: RoutePositionResult? = if (isCartOnline) {
        CampusLandmarkZone.evaluateRoutePosition(
            latitude = cartState?.latitude,
            longitude = cartState?.longitude,
            bearing = cartState?.bearing,
            relativeMovement = cartState?.relativeMovement,
            speedKmH = cartState?.speedKmH ?: 0,
            accuracy = cartState?.accuracy ?: 0f,
            timestamp = cartState?.lastUpdatedMillis ?: System.currentTimeMillis()
        )
    } else null

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(18.dp)
                .fillMaxWidth()
        ) {
            // Header Row: 🚗 Campus Cart & LIVE badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🚗",
                        fontSize = 20.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Campus Cart",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (isLocationFresh) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFDCFCE7),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF86EFAC))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
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
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF1F5F9),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCBD5E1))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF94A3B8))
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (!isDriverAvailable || cartState?.status == GolfCartStatus.OFFLINE) "OFFLINE" else "UPDATING",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF64748B)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Vertical Live Route Timeline with continuous animated cart slider
            VerticalRouteTimeline(
                routeResult = routeResult,
                isLive = isLocationFresh
            )

            Spacer(modifier = Modifier.height(16.dp))
            Divider(color = Color(0xFFF1F5F9), thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))

            // Status Summary Footer
            if (!isDriverAvailable || cartState?.status == GolfCartStatus.OFFLINE) {
                Text(
                    text = "● Golf cart is currently offline",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF64748B)
                )
            } else if (routeResult == null || !isLocationFresh) {
                Text(
                    text = "● Location updating…",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF64748B)
                )
            } else if (routeResult.isAtGate && facultySelectedLocation == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "✓",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF16A34A)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Arrived at Gate",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF15803D)
                    )
                }
            } else {
                val primaryText = routeResult.driverDetailedLocation
                val subtitleText = if (facultySelectedLocation != null) {
                    routeResult.getFacultySubtitle(facultySelectedLocation)
                } else {
                    routeResult.studentSubtitleText
                }

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "📍",
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = primaryText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 22.dp)
                    ) {
                        Text(
                            text = if (subtitleText.startsWith("✓")) "" else "↓ ",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (subtitleText.startsWith("✓")) Color(0xFF16A34A) else Color(0xFF2563EB)
                        )
                        Text(
                            text = subtitleText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (subtitleText.startsWith("✓")) Color(0xFF15803D) else Color(0xFF2563EB)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Vertical Route Timeline displaying the 4 canonical stops with fluid continuous cart position transitions.
 */
@Composable
private fun VerticalRouteTimeline(
    routeResult: RoutePositionResult?,
    isLive: Boolean
) {
    val stops = CampusLandmarkZone.ROUTE_SEQUENCE
    val nodeStepHeight = 44.dp
    val nodeSize = 22.dp
    val totalSteps = (stops.size - 1).toFloat() // 3 segments: 0.0 -> 3.0

    // Smooth transition animation for the cart progress along the route
    val animatedProgress by animateFloatAsState(
        targetValue = if (isLive && routeResult != null) routeResult.routeProgressFloat.coerceIn(0.0f, totalSteps) else 0.0f,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "fluidCartProgress"
    )

    // Pulse animation for the live cart indicator
    val infiniteTransition = rememberInfiniteTransition(label = "cartPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        // 1. Background Route Track (Continuous Vertical Bar from Stop 0 to Stop 3)
        val trackStartOffset = nodeSize / 2
        val totalTrackHeight = nodeStepHeight * (stops.size - 1)

        // Completed Track Progress Height
        val completedHeightFraction = (animatedProgress / totalSteps).coerceIn(0.0f, 1.0f)
        val completedTrackHeight = totalTrackHeight * completedHeightFraction

        // Base Track Line (Gray)
        Box(
            modifier = Modifier
                .padding(start = trackStartOffset - 1.5.dp, top = trackStartOffset)
                .width(3.dp)
                .height(totalTrackHeight)
                .clip(RoundedCornerShape(1.5.dp))
                .background(Color(0xFFE2E8F0))
        )

        // Completed Track Progress Line (Green/Blue Active Gradient)
        if (isLive) {
            Box(
                modifier = Modifier
                    .padding(start = trackStartOffset - 1.5.dp, top = trackStartOffset)
                    .width(3.dp)
                    .height(completedTrackHeight)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF16A34A), Color(0xFF3B82F6))
                        )
                    )
            )
        }

        // 2. Stop Rows (Main Gate, Trunkut, Computer Centre, Hostel)
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            stops.forEachIndexed { index, landmark ->
                val stopInfo = routeResult?.timelineStops?.find { it.landmark == landmark }
                val visualState = if (isLive && stopInfo != null) stopInfo.visualState else StopVisualState.FUTURE_STOP
                val isAtThisStop = isLive && stopInfo?.isAtThisStop == true

                val nodeBgColor by animateColorAsState(
                    targetValue = when {
                        !isLive -> Color(0xFFE2E8F0)
                        isAtThisStop -> Color(0xFF2563EB)
                        visualState == StopVisualState.COMPLETED -> Color(0xFF16A34A)
                        visualState == StopVisualState.NEXT_STOP -> Color(0xFF3B82F6)
                        else -> Color(0xFFF8FAFC)
                    },
                    animationSpec = tween(400),
                    label = "nodeBg_$index"
                )

                val nodeBorderColor by animateColorAsState(
                    targetValue = when {
                        !isLive -> Color(0xFFCBD5E1)
                        isAtThisStop -> Color(0xFF93C5FD)
                        visualState == StopVisualState.COMPLETED -> Color(0xFF86EFAC)
                        visualState == StopVisualState.NEXT_STOP -> Color(0xFF93C5FD)
                        else -> Color(0xFFCBD5E1)
                    },
                    animationSpec = tween(400),
                    label = "nodeBorder_$index"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(nodeStepHeight),
                    verticalAlignment = Alignment.Top
                ) {
                    // Stop Circle Node
                    Box(
                        modifier = Modifier
                            .size(nodeSize)
                            .clip(CircleShape)
                            .background(nodeBgColor)
                            .border(1.5.dp, nodeBorderColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            visualState == StopVisualState.COMPLETED && !isAtThisStop -> {
                                Text(
                                    text = "✓",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                            }
                            visualState == StopVisualState.NEXT_STOP && !isAtThisStop -> {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                            isAtThisStop -> {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                            else -> {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF94A3B8))
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Stop Name & Status Label
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = landmark.displayName,
                            fontSize = 14.sp,
                            fontWeight = if (isAtThisStop || visualState == StopVisualState.NEXT_STOP) FontWeight.Bold else FontWeight.Medium,
                            color = if (isAtThisStop) Color(0xFF1D4ED8) else if (visualState == StopVisualState.COMPLETED) Color(0xFF1E293B) else Color(0xFF64748B)
                        )

                        if (isLive && stopInfo != null) {
                            val badgeBg = when (visualState) {
                                StopVisualState.COMPLETED -> Color(0xFFF1F5F9)
                                StopVisualState.AT_STOP -> Color(0xFFDCFCE7)
                                StopVisualState.NEXT_STOP -> Color(0xFFDBEAFE)
                                StopVisualState.FUTURE_STOP -> Color(0xFFF8FAFC)
                            }
                            val badgeText = when (visualState) {
                                StopVisualState.COMPLETED -> Color(0xFF64748B)
                                StopVisualState.AT_STOP -> Color(0xFF15803D)
                                StopVisualState.NEXT_STOP -> Color(0xFF1D4ED8)
                                StopVisualState.FUTURE_STOP -> Color(0xFF94A3B8)
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = badgeBg
                            ) {
                                Text(
                                    text = stopInfo.statusLabel,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeText,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Fluid Gliding Cart Marker (Positioned smoothly along the vertical route)
        if (isLive) {
            val cartYOffset = (nodeStepHeight * animatedProgress) - (12.dp)

            Box(
                modifier = Modifier
                    .offset(x = 28.dp, y = cartYOffset)
                    .shadow(elevation = 4.dp, shape = RoundedCornerShape(10.dp))
                    .background(Color(0xFF2563EB), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF93C5FD), RoundedCornerShape(10.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🚗",
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (routeResult?.isAtLandmark == true) "At Stop" else "Campus Cart",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}
