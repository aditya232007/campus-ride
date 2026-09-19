package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.GolfCartState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

/**
 * Tile cache manager for OpenStreetMap standard tiles.
 * Provides resilient in-memory LruCache and local disk caching so maps work fast and offline.
 */
object OsmTileManager {
    private const val USER_AGENT = "CampusRide-IIITBhagalpur/1.0 (Android; OpenStreetMap Client)"
    private val memoryCache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val activeLoads = mutableSetOf<String>()

    fun getCachedTile(key: String): Bitmap? = memoryCache.get(key)

    suspend fun loadTile(context: Context, zoom: Int, x: Int, y: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$zoom/$x/$y"
        memoryCache.get(key)?.let { return@withContext it }

        synchronized(activeLoads) {
            if (activeLoads.contains(key)) return@withContext null
            activeLoads.add(key)
        }

        try {
            // Check disk cache first
            val diskDir = File(context.cacheDir, "osm_tiles/$zoom/$x")
            val diskFile = File(diskDir, "$y.png")
            if (diskFile.exists() && diskFile.length() > 0) {
                val diskBitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (diskBitmap != null) {
                    memoryCache.put(key, diskBitmap)
                    return@withContext diskBitmap
                }
            }

            // Download from OpenStreetMap tile servers
            val tileUrl = "https://tile.openstreetmap.org/$zoom/$x/$y.png"
            val connection = URL(tileUrl).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = true

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val bytes = connection.inputStream.readBytes()
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    memoryCache.put(key, bitmap)
                    try {
                        diskDir.mkdirs()
                        FileOutputStream(diskFile).use { it.write(bytes) }
                    } catch (_: Exception) {}
                    return@withContext bitmap
                }
            }
        } catch (_: Exception) {
            // Network fallback: offline vector styling is seamlessly drawn
        } finally {
            synchronized(activeLoads) {
                activeLoads.remove(key)
            }
        }
        null
    }
}

/**
 * Coordinate transformations for Web Mercator (EPSG:3857) standard used by OpenStreetMap.
 */
object WebMercatorProjection {
    fun lonToTileX(lon: Double, zoom: Int): Double =
        (lon + 180.0) / 360.0 * (1 shl zoom)

    fun latToTileY(lat: Double, zoom: Int): Double {
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * (1 shl zoom)
    }

    fun tileXToLon(x: Double, zoom: Int): Double =
        x / (1 shl zoom) * 360.0 - 180.0

    fun tileYToLat(y: Double, zoom: Int): Double {
        val n = Math.PI - 2.0 * Math.PI * y / (1 shl zoom)
        return Math.toDegrees(atan(sinh(n)))
    }
}

/**
 * Campus landmarks for IIIT Bhagalpur
 */
data class CampusStopLocation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val iconEmoji: String,
    val color: Color
)

val CAMPUS_STOPS = listOf(
    CampusStopLocation("MAIN_GATE", "Main Gate", 25.2531616, 87.0370730, "🚪", Color(0xFF16A34A)),
    CampusStopLocation("TRUNKET", "Trunket", 25.2577186, 87.0381730, "📍", Color(0xFF2563EB)),
    CampusStopLocation("COMPUTER_CENTRE", "Computer Centre", 25.2590500, 87.0394730, "💻", Color(0xFF7C3AED)),
    CampusStopLocation("ACADEMIC_BLOCK", "Academic Block", 25.2590750, 87.0401610, "🏛️", Color(0xFF0D9488)),
    CampusStopLocation("HOSTEL", "Hostel", 25.2577810, 87.0418910, "🏠", Color(0xFFD97706))
)

val CANONICAL_ROUTE = listOf(
    Pair(25.2531616, 87.0370730), // Main Gate
    Pair(25.2577186, 87.0381730), // Trunket
    Pair(25.2590500, 87.0394730), // Computer Centre
    Pair(25.2590750, 87.0401610), // Academic Block
    Pair(25.2577810, 87.0418910)  // Hostel
)

/**
 * 100% Free, Open-Source OpenStreetMap component with live Golf Cart and Student location tracking.
 * Requires NO Google Cloud billing account or paid Maps API keys.
 */
@Composable
fun CampusOpenStreetMapView(
    cartState: GolfCartState? = null,
    cart1State: GolfCartState? = null,
    cart2State: GolfCartState? = null,
    studentLatitude: Double? = null,
    studentLongitude: Double? = null,
    isDriverView: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current.density

    // Resolve Cart 1 and Cart 2 independently
    val effectiveCart1 = cart1State ?: if (cartState?.cartId == "cart_1") cartState else null
    val effectiveCart2 = cart2State ?: if (cartState?.cartId == "cart_2") cartState else null

    // Smoothly animate Cart 1 and Cart 2 position and rotation between coordinate updates
    val cart1Anim = rememberAnimatedCartMarkerState(
        targetLat = effectiveCart1?.latitude,
        targetLng = effectiveCart1?.longitude,
        targetBearing = effectiveCart1?.bearing,
        durationMs = 1200
    )
    val cart2Anim = rememberAnimatedCartMarkerState(
        targetLat = effectiveCart2?.latitude,
        targetLng = effectiveCart2?.longitude,
        targetBearing = effectiveCart2?.bearing,
        durationMs = 1200
    )

    // Default center on IIIT Bhagalpur campus
    var centerLat by remember { mutableDoubleStateOf(25.2575) }
    var centerLng by remember { mutableDoubleStateOf(87.0392) }
    var zoom by remember { mutableFloatStateOf(16.5f) }

    // Pulsing animations for live markers
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    // Track tile load updates to trigger canvas repaint
    var tileRefreshTrigger by remember { mutableIntStateOf(0) }

    // Follow cart when it first becomes active
    var hasAutoCentered by remember { mutableStateOf(false) }
    LaunchedEffect(effectiveCart1?.latitude, effectiveCart1?.longitude, effectiveCart2?.latitude, effectiveCart2?.longitude) {
        val lat = effectiveCart1?.latitude ?: effectiveCart2?.latitude
        val lng = effectiveCart1?.longitude ?: effectiveCart2?.longitude
        if (lat != null && lng != null && lat != 0.0 && lng != 0.0 && !hasAutoCentered) {
            hasAutoCentered = true
            centerLat = lat
            centerLng = lng
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFE2E8F0))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoomFactor, _ ->
                        zoom = (zoom * zoomFactor).coerceIn(14.5f, 19.0f)
                        val zoomInt = zoom.toInt().coerceIn(14, 18)
                        val scaleFactor = 2.0.pow((zoom - zoomInt).toDouble())

                        val dxTiles = -pan.x / (256.0 * scaleFactor)
                        val dyTiles = -pan.y / (256.0 * scaleFactor)

                        val currentTileX = WebMercatorProjection.lonToTileX(centerLng, zoomInt) + dxTiles
                        val currentTileY = WebMercatorProjection.latToTileY(centerLat, zoomInt) + dyTiles

                        centerLng = WebMercatorProjection.tileXToLon(currentTileX, zoomInt)
                        centerLat = WebMercatorProjection.tileYToLat(currentTileY, zoomInt)
                    }
                }
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val zoomInt = zoom.toInt().coerceIn(14, 18)
            val scaleFactor = 2.0.pow((zoom - zoomInt).toDouble())

            val centerTileX = WebMercatorProjection.lonToTileX(centerLng, zoomInt)
            val centerTileY = WebMercatorProjection.latToTileY(centerLat, zoomInt)

            fun latLngToScreen(lat: Double, lng: Double): Offset {
                val tx = WebMercatorProjection.lonToTileX(lng, zoomInt)
                val ty = WebMercatorProjection.latToTileY(lat, zoomInt)
                val sx = canvasWidth / 2f + ((tx - centerTileX) * 256.0 * scaleFactor).toFloat()
                val sy = canvasHeight / 2f + ((ty - centerTileY) * 256.0 * scaleFactor).toFloat()
                return Offset(sx, sy)
            }

            // 1. Draw Base Map Canvas Terrain (Warm subtle neutral canvas)
            drawRect(
                color = Color(0xFFF1F5F9),
                size = Size(canvasWidth, canvasHeight)
            )

            // 2. Render OpenStreetMap Slippy Tiles
            val halfW = canvasWidth / 2f
            val halfH = canvasHeight / 2f
            val minTileX = floor(centerTileX - halfW / (256.0 * scaleFactor)).toInt() - 1
            val maxTileX = ceil(centerTileX + halfW / (256.0 * scaleFactor)).toInt() + 1
            val minTileY = floor(centerTileY - halfH / (256.0 * scaleFactor)).toInt() - 1
            val maxTileY = ceil(centerTileY + halfH / (256.0 * scaleFactor)).toInt() + 1

            for (tx in minTileX..maxTileX) {
                for (ty in minTileY..maxTileY) {
                    val tileKey = "$zoomInt/$tx/$ty"
                    val cachedBitmap = OsmTileManager.getCachedTile(tileKey)
                    val tileLeft = halfW + ((tx - centerTileX) * 256.0 * scaleFactor).toFloat()
                    val tileTop = halfH + ((ty - centerTileY) * 256.0 * scaleFactor).toFloat()
                    val tileSize = (256.0 * scaleFactor).toFloat()

                    if (cachedBitmap != null) {
                        drawImage(
                            image = cachedBitmap.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(tileLeft.roundToInt(), tileTop.roundToInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(ceil(tileSize).toInt(), ceil(tileSize).toInt())
                        )
                    } else {
                        // Request asynchronous tile download
                        coroutineScope.launch {
                            val loaded = OsmTileManager.loadTile(context, zoomInt, tx, ty)
                            if (loaded != null) {
                                tileRefreshTrigger++
                            }
                        }
                    }
                }
            }

            // Read tileRefreshTrigger to ensure Compose redraws when new tiles load
            if (tileRefreshTrigger < 0) return@Canvas

            // 3. Draw Campus Grounds Boundary Accent
            val campusOutline = listOf(
                Pair(25.2520, 87.0355),
                Pair(25.2605, 87.0370),
                Pair(25.2600, 87.0435),
                Pair(25.2525, 87.0420)
            )
            val campusPath = Path().apply {
                val start = latLngToScreen(campusOutline.first().first, campusOutline.first().second)
                moveTo(start.x, start.y)
                for (i in 1 until campusOutline.size) {
                    val pt = latLngToScreen(campusOutline[i].first, campusOutline[i].second)
                    lineTo(pt.x, pt.y)
                }
                close()
            }
            drawPath(
                path = campusPath,
                color = Color(0x1210B981) // Soft green campus grounds tint
            )
            drawPath(
                path = campusPath,
                color = Color(0x3310B981),
                style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f))
            )

            // 4. Draw Main Gate Pickup Geofence Zone (70m Radius)
            val gatePt = latLngToScreen(25.2531616, 87.0370730)
            // 70 meters in pixels at current latitude and zoom
            val metersPerPixel = 156543.03392 * cos(Math.toRadians(25.2531616)) / (2.0.pow(zoom.toDouble()))
            val geofenceRadiusPx = (70.0 / metersPerPixel).toFloat().coerceIn(18f, 160f)

            drawCircle(
                color = Color(0x2216A34A),
                radius = geofenceRadiusPx,
                center = gatePt
            )
            drawCircle(
                color = Color(0x6616A34A),
                radius = geofenceRadiusPx,
                center = gatePt,
                style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f))
            )

            // 5. Draw Canonical Polyline Route (Main Gate <-> Trunket <-> Computer Centre <-> Hostel)
            val routePath = Path().apply {
                val start = latLngToScreen(CANONICAL_ROUTE.first().first, CANONICAL_ROUTE.first().second)
                moveTo(start.x, start.y)
                for (i in 1 until CANONICAL_ROUTE.size) {
                    val pt = latLngToScreen(CANONICAL_ROUTE[i].first, CANONICAL_ROUTE[i].second)
                    lineTo(pt.x, pt.y)
                }
            }

            // Outer dark blue road casing
            drawPath(
                path = routePath,
                color = Color(0xFF1E3A8A),
                style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            // Inner vibrant blue route line
            drawPath(
                path = routePath,
                color = Color(0xFF3B82F6),
                style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            // Core cyan highlight
            drawPath(
                path = routePath,
                color = Color(0xFF93C5FD),
                style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            // 6. Draw Fixed Campus Stops
            for (stop in CAMPUS_STOPS) {
                val pt = latLngToScreen(stop.latitude, stop.longitude)

                // Stop marker circle
                drawCircle(
                    color = Color.White,
                    radius = 11f,
                    center = pt
                )
                drawCircle(
                    color = stop.color,
                    radius = 9f,
                    center = pt
                )
                drawCircle(
                    color = Color.White,
                    radius = 4f,
                    center = pt
                )
            }

            // 7. Draw Student Location Marker (if available)
            if (studentLatitude != null && studentLongitude != null && studentLatitude != 0.0 && studentLongitude != 0.0) {
                val studentPt = latLngToScreen(studentLatitude, studentLongitude)

                // Animated Radar Wave
                drawCircle(
                    color = Color(0xFF2563EB).copy(alpha = pulseAlpha),
                    radius = 16f * pulseScale,
                    center = studentPt
                )

                // Outer Halo
                drawCircle(
                    color = Color.White,
                    radius = 11f,
                    center = studentPt
                )
                // Solid Blue Student Dot
                drawCircle(
                    color = Color(0xFF2563EB),
                    radius = 8f,
                    center = studentPt
                )
                drawCircle(
                    color = Color.White,
                    radius = 3.5f,
                    center = studentPt
                )
            }

            // 8. Draw Real-Time Validated Golf Cart Markers with smooth motion interpolation
            // We draw both Cart 1 and Cart 2 independently with smooth position and bearing animations
            val cartsToDraw = listOfNotNull(
                effectiveCart1?.let { if (cart1Anim.hasValidLocation) CartDrawItem(it, 1, Color(0xFF16A34A), cart1Anim) else null },
                effectiveCart2?.let { if (cart2Anim.hasValidLocation) CartDrawItem(it, 2, Color(0xFF2563EB), cart2Anim) else null }
            )

            for ((cart, cartNum, baseColor, animState) in cartsToDraw) {
                val cartLat = animState.latitude
                val cartLng = animState.longitude
                if (cartLat == 0.0 || cartLng == 0.0) continue

                val cartPt = latLngToScreen(cartLat, cartLng)
                val cartPresence = cart.presenceState
                val isFresh = cartPresence == com.example.data.model.CartPresenceState.ONLINE_LOCATION_AVAILABLE
                val isStale = cartPresence == com.example.data.model.CartPresenceState.ONLINE_LOCATION_STALE

                val markerColor = when {
                    isFresh -> baseColor
                    isStale -> Color(0xFFD97706)
                    else -> Color(0xFF64748B)
                }
                val arrowColor = when {
                    isFresh -> if (cartNum == 1) Color(0xFF047857) else Color(0xFF1D4ED8)
                    isStale -> Color(0xFFB45309)
                    else -> Color(0xFF475569)
                }

                // Radar pulse around cart ONLY when live/fresh
                if (isFresh) {
                    drawCircle(
                        color = markerColor.copy(alpha = pulseAlpha),
                        radius = 26f * pulseScale,
                        center = cartPt
                    )
                }

                // White backdrop
                drawCircle(
                    color = Color.White,
                    radius = 17f,
                    center = cartPt
                )
                // Vehicle badge
                drawCircle(
                    color = markerColor,
                    radius = 14f,
                    center = cartPt
                )

                // Smoothly animated bearing heading direction arrow
                val bearing = animState.bearing
                val bearingRad = Math.toRadians((bearing - 90.0))
                val arrowLen = 19f
                val tipX = cartPt.x + (cos(bearingRad) * arrowLen).toFloat()
                val tipY = cartPt.y + (sin(bearingRad) * arrowLen).toFloat()

                val arrowPath = Path().apply {
                    moveTo(tipX, tipY)
                    val leftAngle = bearingRad + Math.toRadians(140.0)
                    val rightAngle = bearingRad - Math.toRadians(140.0)
                    lineTo(
                        (cartPt.x + cos(leftAngle) * 9f).toFloat(),
                        (cartPt.y + sin(leftAngle) * 9f).toFloat()
                    )
                    lineTo(
                        (cartPt.x + cos(rightAngle) * 9f).toFloat(),
                        (cartPt.y + sin(rightAngle) * 9f).toFloat()
                    )
                    close()
                }
                drawPath(
                    path = arrowPath,
                    color = arrowColor
                )

                // Inner white center
                drawCircle(
                    color = Color.White,
                    radius = 5f,
                    center = cartPt
                )

                // Floating label pill above cart: "CART 1" or "CART 2"
                val nativeCanvas = drawContext.canvas.nativeCanvas
                val labelText = "CART $cartNum"
                val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    textSize = 10.5f * density
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    isFakeBoldText = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val textBounds = android.graphics.Rect()
                labelPaint.getTextBounds(labelText, 0, labelText.length, textBounds)
                val pillW = textBounds.width() + (14f * density)
                val pillH = textBounds.height() + (8f * density)
                val pillLeft = cartPt.x - (pillW / 2f)
                val pillTop = cartPt.y - (24f * density) - pillH

                val pillBgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(235, 15, 23, 42)
                    style = android.graphics.Paint.Style.FILL
                }
                val pillBorderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (cartNum == 1) android.graphics.Color.rgb(34, 197, 94) else android.graphics.Color.rgb(59, 130, 246)
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 1.8f * density
                }
                val pillRectF = android.graphics.RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH)
                nativeCanvas.drawRoundRect(pillRectF, 6f * density, 6f * density, pillBgPaint)
                nativeCanvas.drawRoundRect(pillRectF, 6f * density, 6f * density, pillBorderPaint)

                val labelY = pillTop + (pillH / 2f) - ((labelPaint.descent() + labelPaint.ascent()) / 2f)
                nativeCanvas.drawText(labelText, cartPt.x, labelY, labelPaint)
            }
        }

        // Overlay 1: Live Status Header Pill (Cart info & stops)
        val isC1Outside = effectiveCart1?.isOutsideCampus == true || effectiveCart1?.isInsideCampus == false
        val cart1Presence = if (isC1Outside) com.example.data.model.CartPresenceState.OFFLINE else (effectiveCart1?.presenceState ?: com.example.data.model.CartPresenceState.OFFLINE)
        val isC2Outside = effectiveCart2?.isOutsideCampus == true || effectiveCart2?.isInsideCampus == false
        val cart2Presence = if (isC2Outside) com.example.data.model.CartPresenceState.OFFLINE else (effectiveCart2?.presenceState ?: com.example.data.model.CartPresenceState.OFFLINE)
        val isAnyCartLive = cart1Presence.isLocationAvailable || cart2Presence.isLocationAvailable

        val statusDotColor = when {
            isAnyCartLive -> Color(0xFF16A34A)
            cart1Presence == com.example.data.model.CartPresenceState.ONLINE_LOCATION_STALE ||
            cart2Presence == com.example.data.model.CartPresenceState.ONLINE_LOCATION_STALE -> Color(0xFFD97706)
            cart1Presence == com.example.data.model.CartPresenceState.ONLINE_NO_LOCATION ||
            cart2Presence == com.example.data.model.CartPresenceState.ONLINE_NO_LOCATION -> Color(0xFF0284C7)
            isC1Outside || isC2Outside -> Color(0xFFDC2626)
            else -> Color(0xFF94A3B8)
        }

        val headerText = when {
            cart1Presence.isLocationAvailable && cart2Presence.isLocationAvailable -> {
                "Cart 1: ${effectiveCart1?.currentStop ?: "Active"}  •  Cart 2: ${effectiveCart2?.currentStop ?: "Active"}"
            }
            cart1Presence.isLocationAvailable -> {
                val c2Label = if (isC2Outside) "Not Available" else "Offline"
                "Cart 1: ${effectiveCart1?.currentStop ?: "Active"}  •  Cart 2: $c2Label"
            }
            cart2Presence.isLocationAvailable -> {
                val c1Label = if (isC1Outside) "Not Available" else "Offline"
                "Cart 1: $c1Label  •  Cart 2: ${effectiveCart2?.currentStop ?: "Active"}"
            }
            isC1Outside || isC2Outside -> {
                "Driver Not Available • Outside Campus"
            }
            cart1Presence == com.example.data.model.CartPresenceState.ONLINE_NO_LOCATION ||
            cart2Presence == com.example.data.model.CartPresenceState.ONLINE_NO_LOCATION -> {
                "Campus Carts Syncing Location..."
            }
            else -> {
                "Carts Offline • IIIT Bhagalpur"
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shadowElevation = 3.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusDotColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = headerText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Overlay 2: Floating Navigation Controls
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Recenter on Active Cart (Cart 1 or Cart 2)
            val primaryCartLat = effectiveCart1?.latitude ?: effectiveCart2?.latitude
            val primaryCartLng = effectiveCart1?.longitude ?: effectiveCart2?.longitude
            if (primaryCartLat != null && primaryCartLng != null && primaryCartLat != 0.0 && primaryCartLng != 0.0) {
                SmallFloatingActionButton(
                    onClick = {
                        centerLat = primaryCartLat
                        centerLng = primaryCartLng
                        zoom = 17.0f
                    },
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    contentColor = Color(0xFF16A34A),
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NearMe,
                        contentDescription = "Recenter on Cart",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Recenter on Student Location (if available)
            if (studentLatitude != null && studentLongitude != null && studentLatitude != 0.0 && studentLongitude != 0.0) {
                SmallFloatingActionButton(
                    onClick = {
                        centerLat = studentLatitude
                        centerLng = studentLongitude
                        zoom = 17.2f
                    },
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    contentColor = Color(0xFF2563EB),
                    shape = CircleShape,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Recenter on My Location",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Fit Campus Bounds
            SmallFloatingActionButton(
                onClick = {
                    centerLat = 25.2575
                    centerLng = 87.0392
                    zoom = 16.5f
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

            // Zoom In (+)
            SmallFloatingActionButton(
                onClick = { zoom = (zoom + 0.6f).coerceAtMost(19.0f) },
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Zoom In",
                    modifier = Modifier.size(16.dp)
                )
            }

            // Zoom Out (-)
            SmallFloatingActionButton(
                onClick = { zoom = (zoom - 0.6f).coerceAtLeast(14.5f) },
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Zoom Out",
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Overlay 3: Attribution Footer (legal compliance)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp),
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        ) {
            Text(
                text = "© OpenStreetMap",
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

private data class CartDrawItem(
    val cart: GolfCartState,
    val cartNum: Int,
    val baseColor: Color,
    val animState: AnimatedCartMarkerState
)
