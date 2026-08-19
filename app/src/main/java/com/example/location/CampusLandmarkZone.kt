package com.example.location

import android.util.Log
import java.util.ArrayDeque
import kotlin.math.roundToInt

/**
 * Robust Campus Landmark Zones, connected route model, and GPS Noise Filter for IIIT Bhagalpur.
 *
 * Canonical Fixed Route:
 * MAIN GATE -> TRUNKUT -> COMPUTER CENTRE -> HOSTEL
 *
 * Stabilization Features:
 * 1. Accuracy Filtering: Discards or suppresses low-accuracy/noisy readings.
 * 2. Stationary Position Anchor: Rejects < 10m jitter when speed is near zero.
 * 3. Stop Zone Hysteresis: Separate Enter (capture) and Exit (release) radii prevent boundary flipping.
 * 4. Multi-sample Trend Analysis: Requires consistent displacement before declaring Approaching/Departing.
 * 5. State Debouncing: Candidate states must be confirmed across multiple consecutive samples.
 * 6. Hidden Developer Logging: Detailed diagnostics logged to Logcat under "CampusGpsFilter".
 */
enum class CampusLandmarkZone(
    val id: String,
    val displayName: String,
    val fullAddress: String,
    val emoji: String,
    val latitude: Double,
    val longitude: Double,
    val enterRadiusMeters: Double,
    val exitRadiusMeters: Double,
    val isVerifiedCoordinate: Boolean
) {
    GATE(
        id = "MAIN_GATE",
        displayName = "Main Gate",
        fullAddress = "IIIT BHAGALPUR Main Gate, Bhagalpur, Bihar 813210",
        emoji = "🚪",
        latitude = 25.2531616,
        longitude = 87.0370730,
        enterRadiusMeters = 55.0,
        exitRadiusMeters = 80.0,
        isVerifiedCoordinate = true
    ),
    TRUNKUT(
        id = "TRUNKUT",
        displayName = "Trunkut",
        fullAddress = "Trunkut, IIIT Bhagalpur Campus, Sabour, Bihar 813210",
        emoji = "📍",
        latitude = 25.2577186,
        longitude = 87.0381730,
        enterRadiusMeters = 45.0,
        exitRadiusMeters = 70.0,
        isVerifiedCoordinate = true
    ),
    COMPUTER_CENTRE(
        id = "COMPUTER_CENTRE",
        displayName = "Computer Centre",
        fullAddress = "Computer Centre, IIIT Bhagalpur Campus, Sabour, Bihar 813210",
        emoji = "💻",
        latitude = 25.2590500,
        longitude = 87.0394730,
        enterRadiusMeters = 45.0,
        exitRadiusMeters = 70.0,
        isVerifiedCoordinate = true
    ),
    HOSTEL(
        id = "HOSTEL",
        displayName = "Hostel",
        fullAddress = "Boys Hostel, IIIT Bhagalpur Campus, Sabour, Bihar 813210",
        emoji = "🏠",
        latitude = 25.2577810,
        longitude = 87.0418910,
        enterRadiusMeters = 65.0,
        exitRadiusMeters = 90.0,
        isVerifiedCoordinate = true
    );

    val arrivalRadiusMeters: Double get() = enterRadiusMeters

    companion object {
        private const val TAG = "CampusGpsFilter"

        // Thresholds
        const val MAX_ACCEPTED_ACCURACY_METERS = 30.0f
        const val MIN_MOVEMENT_THRESHOLD_METERS = 10.0 // Minimum displacement from anchor to count as movement
        const val MIN_SPEED_THRESHOLD_KMH = 2.5
        const val REQUIRED_CONFIRMATION_SAMPLES = 3 // Number of consecutive samples to transition state
        const val MIN_TREND_DELTA_METERS = 8.0 // Monotonic distance delta needed to confirm approaching/moving away

        // Ordered sequence: Main Gate -> Trunkut -> Computer Centre -> Hostel
        val ROUTE_SEQUENCE = listOf(GATE, TRUNKUT, COMPUTER_CENTRE, HOSTEL)

        enum class StopVisualState {
            COMPLETED,      // Passed/Crossed
            AT_STOP,        // Currently stopped inside arrival radius
            NEXT_STOP,      // Approaching next stop
            FUTURE_STOP     // Upcoming later
        }

        data class StopTimelineInfo(
            val landmark: CampusLandmarkZone,
            val visualState: StopVisualState,
            val statusLabel: String,
            val isAtThisStop: Boolean
        )

        enum class CartDirection {
            TOWARD_GATE,
            TOWARD_HOSTEL,
            STATIONARY,
            UNKNOWN
        }

        data class RoutePositionResult(
            val primaryLandmark: CampusLandmarkZone,
            val secondaryLandmark: CampusLandmarkZone?,
            val isAtLandmark: Boolean,
            val isBetween: Boolean,
            val movingDirection: CartDirection,
            val isAtGate: Boolean,
            val routeProgressFloat: Float, // 0.0f (Gate) -> 1.0f (Trunkut) -> 2.0f (Computer Centre) -> 3.0f (Hostel)
            val driverDetailedLocation: String,
            val driverDirectionSubtitle: String,
            val studentPrimaryText: String,
            val studentSubtitleText: String,
            val timelineStops: List<StopTimelineInfo>
        ) {
            fun getFacultySubtitle(facultyPickupDisplayName: String): String {
                return when {
                    isAtGate && facultyPickupDisplayName.contains("Gate", ignoreCase = true) -> "✓ Arrived at Main Gate"
                    isAtLandmark && primaryLandmark.displayName.equals(facultyPickupDisplayName, ignoreCase = true) -> "✓ At $facultyPickupDisplayName"
                    movingDirection == CartDirection.TOWARD_GATE && facultyPickupDisplayName.contains("Gate", ignoreCase = true) -> "Coming to Main Gate"
                    else -> "Coming to $facultyPickupDisplayName"
                }
            }
        }

        /**
         * Rolling GPS distance sample for trend smoothing to determine real approaching/departing trends.
         */
        data class DistanceSample(
            val timestamp: Long,
            val lat: Double,
            val lng: Double,
            val distGate: Double,
            val distTrunkut: Double,
            val distCC: Double,
            val distHostel: Double,
            val speedKmH: Int,
            val accuracy: Float
        )

        // Internal State Storage for Smoothing, Hysteresis, and Debouncing
        private val rollingDistanceSamples = ArrayDeque<DistanceSample>()
        private const val MAX_SAMPLES = 6

        private var anchorLat: Double? = null
        private var anchorLng: Double? = null

        private var currentConfirmedStop: CampusLandmarkZone? = null
        private var confirmedRouteResult: RoutePositionResult? = null
        private var candidateRouteResult: RoutePositionResult? = null
        private var candidateConfirmationCount = 0

        @Synchronized
        fun clearRollingHistory() {
            rollingDistanceSamples.clear()
            anchorLat = null
            anchorLng = null
            currentConfirmedStop = null
            confirmedRouteResult = null
            candidateRouteResult = null
            candidateConfirmationCount = 0
            Log.d(TAG, "FILTER: Rolling history and state reset")
        }

        @Synchronized
        private fun recordSample(sample: DistanceSample) {
            if (rollingDistanceSamples.size >= MAX_SAMPLES) {
                rollingDistanceSamples.removeFirst()
            }
            rollingDistanceSamples.addLast(sample)
        }

        /**
         * Main Entry Point: Evaluates the connected route status with robust GPS stabilization,
         * stop-zone hysteresis, and candidate debouncing.
         */
        @Synchronized
        fun evaluateRoutePosition(
            latitude: Double?,
            longitude: Double?,
            bearing: Float? = null,
            relativeMovement: String? = null,
            speedKmH: Int = 0,
            accuracy: Float = 0f,
            timestamp: Long = System.currentTimeMillis()
        ): RoutePositionResult? {
            if (latitude == null || longitude == null || (latitude == 0.0 && longitude == 0.0)) {
                return confirmedRouteResult
            }

            // 1. GPS Accuracy Check
            val isAccuracyAcceptable = accuracy <= 0f || accuracy <= MAX_ACCEPTED_ACCURACY_METERS
            if (!isAccuracyAcceptable) {
                Log.d(TAG, "GPS: REJECTED poor accuracy: $accuracy m (lat=$latitude, lng=$longitude)")
                return confirmedRouteResult
            }

            val distGate = GeofenceManager.calculateDistanceMeters(latitude, longitude, GATE.latitude, GATE.longitude)
            val distTrunkut = GeofenceManager.calculateDistanceMeters(latitude, longitude, TRUNKUT.latitude, TRUNKUT.longitude)
            val distCC = GeofenceManager.calculateDistanceMeters(latitude, longitude, COMPUTER_CENTRE.latitude, COMPUTER_CENTRE.longitude)
            val distHostel = GeofenceManager.calculateDistanceMeters(latitude, longitude, HOSTEL.latitude, HOSTEL.longitude)

            // Campus boundary filter
            if (distGate > 1500.0 && distHostel > 1500.0) {
                Log.d(TAG, "GPS: Outside campus boundary (>1500m)")
                return confirmedRouteResult
            }

            // 2. Stationary Anchor & Noise Filtering
            if (anchorLat == null || anchorLng == null) {
                anchorLat = latitude
                anchorLng = longitude
            }

            val displacementFromAnchor = GeofenceManager.calculateDistanceMeters(latitude, longitude, anchorLat!!, anchorLng!!)
            val isSpeedStationary = speedKmH < MIN_SPEED_THRESHOLD_KMH
            val isDisplacementStationary = displacementFromAnchor < MIN_MOVEMENT_THRESHOLD_METERS
            val isStationary = isSpeedStationary && isDisplacementStationary

            if (!isStationary) {
                // Update anchor when meaningful movement is confirmed
                anchorLat = latitude
                anchorLng = longitude
            }

            // Record into rolling sample queue
            val newSample = DistanceSample(
                timestamp = timestamp,
                lat = latitude,
                lng = longitude,
                distGate = distGate,
                distTrunkut = distTrunkut,
                distCC = distCC,
                distHostel = distHostel,
                speedKmH = speedKmH,
                accuracy = accuracy
            )
            recordSample(newSample)

            // 3. Multi-sample Distance Trend Calculation (over window)
            val oldest = rollingDistanceSamples.firstOrNull()
            val deltaGate = if (oldest != null) distGate - oldest.distGate else 0.0
            val deltaTrunkut = if (oldest != null) distTrunkut - oldest.distTrunkut else 0.0
            val deltaCC = if (oldest != null) distCC - oldest.distCC else 0.0
            val deltaHostel = if (oldest != null) distHostel - oldest.distHostel else 0.0

            val isApproachingHostel = !isStationary && deltaHostel <= -MIN_TREND_DELTA_METERS
            val isMovingAwayFromHostel = !isStationary && deltaHostel >= MIN_TREND_DELTA_METERS
            val isApproachingCC = !isStationary && deltaCC <= -MIN_TREND_DELTA_METERS
            val isApproachingTrunkut = !isStationary && deltaTrunkut <= -MIN_TREND_DELTA_METERS
            val isApproachingGate = !isStationary && deltaGate <= -MIN_TREND_DELTA_METERS

            // Direction calculation combining speed, rolling distance trends, bearing, and relative movement
            val movingDirection: CartDirection = when {
                isStationary -> CartDirection.STATIONARY
                isApproachingHostel || (deltaCC > MIN_TREND_DELTA_METERS && !isApproachingGate) -> CartDirection.TOWARD_HOSTEL
                isApproachingGate || (deltaHostel > MIN_TREND_DELTA_METERS && !isApproachingHostel) -> CartDirection.TOWARD_GATE
                relativeMovement == "Coming Towards You" -> CartDirection.TOWARD_GATE
                relativeMovement == "Moving Away" -> CartDirection.TOWARD_HOSTEL
                bearing != null && bearing in 120.0f..240.0f -> CartDirection.TOWARD_GATE
                bearing != null && (bearing in 300.0f..360.0f || bearing in 0.0f..60.0f) -> CartDirection.TOWARD_HOSTEL
                else -> confirmedRouteResult?.movingDirection ?: CartDirection.TOWARD_GATE
            }

            Log.d(
                TAG,
                "GPS: lat=$latitude, lng=$longitude, acc=$accuracy m, spd=$speedKmH km/h | " +
                        "FILTER: anchorDist=${displacementFromAnchor.roundToInt()}m, stationary=$isStationary, dir=$movingDirection"
            )

            // =========================================================================
            // 4. Stop Zone Hysteresis Evaluation
            // =========================================================================

            // If we are currently confirmed AT a stop, check if we remain inside its EXIT radius
            val activeStop = currentConfirmedStop
            if (activeStop != null) {
                val distToActiveStop = when (activeStop) {
                    GATE -> distGate
                    TRUNKUT -> distTrunkut
                    COMPUTER_CENTRE -> distCC
                    HOSTEL -> distHostel
                }

                // If within exit radius OR stationary, lock firmly at this stop!
                if (distToActiveStop <= activeStop.exitRadiusMeters || isStationary) {
                    val stableResult = buildAtStopResult(activeStop, movingDirection)
                    confirmedRouteResult = stableResult
                    candidateRouteResult = null
                    candidateConfirmationCount = 0
                    Log.d(TAG, "STATUS: Firmly locked AT_${activeStop.name} via hysteresis (dist=${distToActiveStop.roundToInt()}m <= exitRadius=${activeStop.exitRadiusMeters}m)")
                    return stableResult
                } else {
                    Log.d(TAG, "STATUS: Exiting ${activeStop.name} zone (dist=${distToActiveStop.roundToInt()}m > exitRadius=${activeStop.exitRadiusMeters}m)")
                    currentConfirmedStop = null
                }
            }

            // Check if entering any stop's ENTER radius
            val newlyEnteredStop = when {
                distHostel <= HOSTEL.enterRadiusMeters -> HOSTEL
                distGate <= GATE.enterRadiusMeters -> GATE
                distCC <= COMPUTER_CENTRE.enterRadiusMeters -> COMPUTER_CENTRE
                distTrunkut <= TRUNKUT.enterRadiusMeters -> TRUNKUT
                else -> null
            }

            if (newlyEnteredStop != null) {
                currentConfirmedStop = newlyEnteredStop
                val atStopResult = buildAtStopResult(newlyEnteredStop, movingDirection)
                confirmedRouteResult = atStopResult
                candidateRouteResult = null
                candidateConfirmationCount = 0
                Log.d(TAG, "STATUS: Newly entered ${newlyEnteredStop.name} arrival radius (${newlyEnteredStop.enterRadiusMeters}m)")
                return atStopResult
            }

            // =========================================================================
            // 5. In-Transit Route Segments Evaluation (Raw Candidate Generation)
            // =========================================================================
            val rawCandidate = evaluateInTransitCandidate(
                distGate = distGate,
                distTrunkut = distTrunkut,
                distCC = distCC,
                distHostel = distHostel,
                movingDirection = movingDirection,
                isApproachingGate = isApproachingGate,
                isApproachingTrunkut = isApproachingTrunkut,
                isApproachingCC = isApproachingCC,
                isApproachingHostel = isApproachingHostel,
                isMovingAwayFromHostel = isMovingAwayFromHostel,
                isStationary = isStationary
            )

            // =========================================================================
            // 6. State Debouncing / Confirmation Buffer
            // =========================================================================
            val currentConfirmed = confirmedRouteResult
            if (currentConfirmed == null) {
                confirmedRouteResult = rawCandidate
                Log.d(TAG, "STATUS: Initial confirmed status: ${rawCandidate.driverDetailedLocation}")
                return rawCandidate
            }

            if (rawCandidate.driverDetailedLocation == currentConfirmed.driverDetailedLocation) {
                // Keep confirmed, reset candidate counter
                candidateRouteResult = null
                candidateConfirmationCount = 0
                confirmedRouteResult = rawCandidate.copy(
                    routeProgressFloat = (currentConfirmed.routeProgressFloat * 0.7f + rawCandidate.routeProgressFloat * 0.3f)
                )
                return confirmedRouteResult
            }

            // A different status is proposed
            if (candidateRouteResult?.driverDetailedLocation == rawCandidate.driverDetailedLocation) {
                candidateConfirmationCount++
                Log.d(
                    TAG,
                    "STATUS: Candidate '${rawCandidate.driverDetailedLocation}' confirmed $candidateConfirmationCount/$REQUIRED_CONFIRMATION_SAMPLES"
                )
                if (candidateConfirmationCount >= REQUIRED_CONFIRMATION_SAMPLES || !isStationary) {
                    confirmedRouteResult = rawCandidate
                    candidateRouteResult = null
                    candidateConfirmationCount = 0
                    Log.d(TAG, "STATUS: Transitioned to CONFIRMED status: ${rawCandidate.driverDetailedLocation}")
                }
            } else {
                candidateRouteResult = rawCandidate
                candidateConfirmationCount = 1
                Log.d(
                    TAG,
                    "STATUS: New candidate proposed: '${rawCandidate.driverDetailedLocation}' (Holding previous: '${currentConfirmed.driverDetailedLocation}')"
                )
            }

            return confirmedRouteResult
        }

        private fun buildAtStopResult(stop: CampusLandmarkZone, direction: CartDirection): RoutePositionResult {
            return when (stop) {
                GATE -> {
                    val stops = listOf(
                        StopTimelineInfo(GATE, StopVisualState.AT_STOP, "At Gate", isAtThisStop = true),
                        StopTimelineInfo(TRUNKUT, StopVisualState.FUTURE_STOP, "Upcoming", isAtThisStop = false),
                        StopTimelineInfo(COMPUTER_CENTRE, StopVisualState.FUTURE_STOP, "Upcoming", isAtThisStop = false),
                        StopTimelineInfo(HOSTEL, StopVisualState.FUTURE_STOP, "Upcoming", isAtThisStop = false)
                    )
                    RoutePositionResult(
                        primaryLandmark = GATE,
                        secondaryLandmark = null,
                        isAtLandmark = true,
                        isBetween = false,
                        movingDirection = CartDirection.TOWARD_GATE,
                        isAtGate = true,
                        routeProgressFloat = 0.0f,
                        driverDetailedLocation = "At Main Gate",
                        driverDirectionSubtitle = "Arrived at Gate",
                        studentPrimaryText = "At Main Gate",
                        studentSubtitleText = "✓ Arrived at Gate",
                        timelineStops = stops
                    )
                }
                HOSTEL -> {
                    val stops = listOf(
                        StopTimelineInfo(GATE, StopVisualState.COMPLETED, "Crossed", isAtThisStop = false),
                        StopTimelineInfo(TRUNKUT, StopVisualState.COMPLETED, "Crossed", isAtThisStop = false),
                        StopTimelineInfo(COMPUTER_CENTRE, StopVisualState.COMPLETED, "Crossed", isAtThisStop = false),
                        StopTimelineInfo(HOSTEL, StopVisualState.AT_STOP, "At Hostel", isAtThisStop = true)
                    )
                    val driverSub = if (direction == CartDirection.TOWARD_GATE) "Moving toward Computer Centre" else "At Hostel"
                    val studentSub = if (direction == CartDirection.TOWARD_GATE) "Coming to Gate" else "At Hostel"
                    RoutePositionResult(
                        primaryLandmark = HOSTEL,
                        secondaryLandmark = null,
                        isAtLandmark = true,
                        isBetween = false,
                        movingDirection = direction,
                        isAtGate = false,
                        routeProgressFloat = 3.0f,
                        driverDetailedLocation = "At Hostel",
                        driverDirectionSubtitle = driverSub,
                        studentPrimaryText = "At Hostel",
                        studentSubtitleText = studentSub,
                        timelineStops = stops
                    )
                }
                TRUNKUT -> {
                    val isHeadingGate = direction == CartDirection.TOWARD_GATE
                    val stops = listOf(
                        StopTimelineInfo(GATE, if (isHeadingGate) StopVisualState.NEXT_STOP else StopVisualState.COMPLETED, if (isHeadingGate) "Next Stop" else "Crossed", isAtThisStop = false),
                        StopTimelineInfo(TRUNKUT, StopVisualState.AT_STOP, "At Trunkut", isAtThisStop = true),
                        StopTimelineInfo(COMPUTER_CENTRE, if (isHeadingGate) StopVisualState.COMPLETED else StopVisualState.NEXT_STOP, if (isHeadingGate) "Crossed" else "Next Stop", isAtThisStop = false),
                        StopTimelineInfo(HOSTEL, if (isHeadingGate) StopVisualState.COMPLETED else StopVisualState.FUTURE_STOP, if (isHeadingGate) "Crossed" else "Upcoming", isAtThisStop = false)
                    )
                    val driverSub = if (isHeadingGate) "Moving toward Main Gate" else "Moving toward Computer Centre"
                    val studentSub = if (isHeadingGate) "Coming to Gate" else "In Transit"
                    RoutePositionResult(
                        primaryLandmark = TRUNKUT,
                        secondaryLandmark = null,
                        isAtLandmark = true,
                        isBetween = false,
                        movingDirection = direction,
                        isAtGate = false,
                        routeProgressFloat = 1.0f,
                        driverDetailedLocation = "At Trunkut",
                        driverDirectionSubtitle = driverSub,
                        studentPrimaryText = "At Trunkut",
                        studentSubtitleText = studentSub,
                        timelineStops = stops
                    )
                }
                COMPUTER_CENTRE -> {
                    val isHeadingGate = direction == CartDirection.TOWARD_GATE
                    val stops = listOf(
                        StopTimelineInfo(GATE, if (isHeadingGate) StopVisualState.FUTURE_STOP else StopVisualState.COMPLETED, if (isHeadingGate) "Upcoming" else "Crossed", isAtThisStop = false),
                        StopTimelineInfo(TRUNKUT, if (isHeadingGate) StopVisualState.NEXT_STOP else StopVisualState.COMPLETED, if (isHeadingGate) "Next Stop" else "Crossed", isAtThisStop = false),
                        StopTimelineInfo(COMPUTER_CENTRE, StopVisualState.AT_STOP, "At Computer Centre", isAtThisStop = true),
                        StopTimelineInfo(HOSTEL, if (isHeadingGate) StopVisualState.COMPLETED else StopVisualState.NEXT_STOP, if (isHeadingGate) "Crossed" else "Next Stop", isAtThisStop = false)
                    )
                    val driverSub = if (isHeadingGate) "Moving toward Trunkut" else "Moving toward Hostel"
                    val studentSub = if (isHeadingGate) "Coming to Gate" else "In Transit"
                    RoutePositionResult(
                        primaryLandmark = COMPUTER_CENTRE,
                        secondaryLandmark = null,
                        isAtLandmark = true,
                        isBetween = false,
                        movingDirection = direction,
                        isAtGate = false,
                        routeProgressFloat = 2.0f,
                        driverDetailedLocation = "At Computer Centre",
                        driverDirectionSubtitle = driverSub,
                        studentPrimaryText = "At Computer Centre",
                        studentSubtitleText = studentSub,
                        timelineStops = stops
                    )
                }
            }
        }

        private fun evaluateInTransitCandidate(
            distGate: Double,
            distTrunkut: Double,
            distCC: Double,
            distHostel: Double,
            movingDirection: CartDirection,
            isApproachingGate: Boolean,
            isApproachingTrunkut: Boolean,
            isApproachingCC: Boolean,
            isApproachingHostel: Boolean,
            isMovingAwayFromHostel: Boolean,
            isStationary: Boolean
        ): RoutePositionResult {
            val isBetweenGateAndTrunkut = (distGate + distTrunkut) < 650.0 || (distGate < 350.0 && distTrunkut < 350.0)
            val isBetweenTrunkutAndCC = (distTrunkut + distCC) < 320.0 || (distTrunkut < 180.0 && distCC < 180.0)
            val isBetweenCCAndHostel = (distCC + distHostel) < 400.0 || (distCC < 220.0 && distHostel < 220.0)

            return when {
                // SEGMENT 1: MAIN GATE <-> TRUNKUT
                isBetweenGateAndTrunkut && distGate < distCC && distGate < distHostel -> {
                    val progressRatio = (distGate / (distGate + distTrunkut).coerceAtLeast(1.0)).toFloat().coerceIn(0.05f, 0.95f)
                    val routeProgress = 0.0f + progressRatio
                    val isHeadingGate = isApproachingGate || movingDirection == CartDirection.TOWARD_GATE

                    val driverSub = if (isHeadingGate) "Approaching Main Gate" else "Approaching Trunkut"
                    val locLabel = if (isHeadingGate) "Between Trunkut & Main Gate" else "Between Main Gate & Trunkut"
                    val studentPrimary = if (isHeadingGate && distGate <= 70.0) "Near Gate" else locLabel
                    val studentSub = if (isHeadingGate && distGate <= 70.0) "Arriving" else if (isHeadingGate) "Approaching Main Gate" else "Approaching Trunkut"

                    val stops = listOf(
                        StopTimelineInfo(GATE, if (isHeadingGate) StopVisualState.NEXT_STOP else StopVisualState.COMPLETED, if (isHeadingGate) "Approaching" else "Crossed", isAtThisStop = false),
                        StopTimelineInfo(TRUNKUT, if (isHeadingGate) StopVisualState.COMPLETED else StopVisualState.NEXT_STOP, if (isHeadingGate) "Crossed" else "Approaching", isAtThisStop = false),
                        StopTimelineInfo(COMPUTER_CENTRE, if (isHeadingGate) StopVisualState.COMPLETED else StopVisualState.FUTURE_STOP, if (isHeadingGate) "Crossed" else "Upcoming", isAtThisStop = false),
                        StopTimelineInfo(HOSTEL, if (isHeadingGate) StopVisualState.COMPLETED else StopVisualState.FUTURE_STOP, if (isHeadingGate) "Crossed" else "Upcoming", isAtThisStop = false)
                    )

                    RoutePositionResult(
                        primaryLandmark = GATE,
                        secondaryLandmark = TRUNKUT,
                        isAtLandmark = false,
                        isBetween = true,
                        movingDirection = if (isHeadingGate) CartDirection.TOWARD_GATE else CartDirection.TOWARD_HOSTEL,
                        isAtGate = false,
                        routeProgressFloat = routeProgress,
                        driverDetailedLocation = locLabel,
                        driverDirectionSubtitle = driverSub,
                        studentPrimaryText = studentPrimary,
                        studentSubtitleText = studentSub,
                        timelineStops = stops
                    )
                }

                // SEGMENT 2: TRUNKUT <-> COMPUTER CENTRE
                isBetweenTrunkutAndCC && distTrunkut < distGate && distCC < distHostel -> {
                    val progressRatio = (distTrunkut / (distTrunkut + distCC).coerceAtLeast(1.0)).toFloat().coerceIn(0.05f, 0.95f)
                    val routeProgress = 1.0f + progressRatio
                    val isHeadingGate = isApproachingTrunkut || movingDirection == CartDirection.TOWARD_GATE

                    val driverSub = if (isHeadingGate) "Approaching Trunkut" else "Approaching Computer Centre"
                    val locLabel = if (isHeadingGate) "Between Computer Centre & Trunkut" else "Between Trunkut & Computer Centre"
                    val studentSub = if (isHeadingGate) "Approaching Trunkut" else "Approaching Computer Centre"

                    val stops = listOf(
                        StopTimelineInfo(GATE, if (isHeadingGate) StopVisualState.FUTURE_STOP else StopVisualState.COMPLETED, if (isHeadingGate) "Upcoming" else "Crossed", isAtThisStop = false),
                        StopTimelineInfo(TRUNKUT, if (isHeadingGate) StopVisualState.NEXT_STOP else StopVisualState.COMPLETED, if (isHeadingGate) "Approaching" else "Crossed", isAtThisStop = false),
                        StopTimelineInfo(COMPUTER_CENTRE, if (isHeadingGate) StopVisualState.COMPLETED else StopVisualState.NEXT_STOP, if (isHeadingGate) "Crossed" else "Approaching", isAtThisStop = false),
                        StopTimelineInfo(HOSTEL, if (isHeadingGate) StopVisualState.COMPLETED else StopVisualState.FUTURE_STOP, if (isHeadingGate) "Crossed" else "Upcoming", isAtThisStop = false)
                    )

                    RoutePositionResult(
                        primaryLandmark = TRUNKUT,
                        secondaryLandmark = COMPUTER_CENTRE,
                        isAtLandmark = false,
                        isBetween = true,
                        movingDirection = if (isHeadingGate) CartDirection.TOWARD_GATE else CartDirection.TOWARD_HOSTEL,
                        isAtGate = false,
                        routeProgressFloat = routeProgress,
                        driverDetailedLocation = locLabel,
                        driverDirectionSubtitle = driverSub,
                        studentPrimaryText = locLabel,
                        studentSubtitleText = studentSub,
                        timelineStops = stops
                    )
                }

                // SEGMENT 3: COMPUTER CENTRE <-> HOSTEL
                isBetweenCCAndHostel || (distHostel < distGate && distCC < distGate) -> {
                    val progressRatio = (distCC / (distCC + distHostel).coerceAtLeast(1.0)).toFloat().coerceIn(0.05f, 0.95f)
                    val routeProgress = 2.0f + progressRatio
                    val isHeadingGate = isApproachingCC || (isMovingAwayFromHostel && !isApproachingHostel) || movingDirection == CartDirection.TOWARD_GATE

                    val driverSub = if (isHeadingGate) "Approaching Computer Centre" else "Approaching Hostel"
                    val locLabel = if (isHeadingGate) "Between Hostel & Computer Centre" else "Between Computer Centre & Hostel"
                    val studentSub = if (isHeadingGate) "Approaching Computer Centre" else "Approaching Hostel"

                    val stops = listOf(
                        StopTimelineInfo(GATE, if (isHeadingGate) StopVisualState.FUTURE_STOP else StopVisualState.COMPLETED, if (isHeadingGate) "Upcoming" else "Crossed", isAtThisStop = false),
                        StopTimelineInfo(TRUNKUT, if (isHeadingGate) StopVisualState.FUTURE_STOP else StopVisualState.COMPLETED, if (isHeadingGate) "Upcoming" else "Crossed", isAtThisStop = false),
                        StopTimelineInfo(COMPUTER_CENTRE, if (isHeadingGate) StopVisualState.NEXT_STOP else StopVisualState.COMPLETED, if (isHeadingGate) "Approaching" else "Crossed", isAtThisStop = false),
                        StopTimelineInfo(HOSTEL, if (isHeadingGate) StopVisualState.COMPLETED else StopVisualState.NEXT_STOP, if (isHeadingGate) "Crossed" else "Approaching", isAtThisStop = false)
                    )

                    RoutePositionResult(
                        primaryLandmark = COMPUTER_CENTRE,
                        secondaryLandmark = HOSTEL,
                        isAtLandmark = false,
                        isBetween = true,
                        movingDirection = if (isHeadingGate) CartDirection.TOWARD_GATE else CartDirection.TOWARD_HOSTEL,
                        isAtGate = false,
                        routeProgressFloat = routeProgress,
                        driverDetailedLocation = locLabel,
                        driverDirectionSubtitle = driverSub,
                        studentPrimaryText = locLabel,
                        studentSubtitleText = studentSub,
                        timelineStops = stops
                    )
                }

                else -> {
                    // Fallback to closest segment based on geometry
                    if (distHostel < distCC && distHostel < distTrunkut) {
                        val isHeadingGate = isApproachingCC || movingDirection == CartDirection.TOWARD_GATE
                        val locLabel = if (isHeadingGate) "Between Hostel & Computer Centre" else "Between Computer Centre & Hostel"
                        val subLabel = if (isHeadingGate) "Approaching Computer Centre" else "Approaching Hostel"
                        val stops = listOf(
                            StopTimelineInfo(GATE, StopVisualState.FUTURE_STOP, "Upcoming", isAtThisStop = false),
                            StopTimelineInfo(TRUNKUT, StopVisualState.FUTURE_STOP, "Upcoming", isAtThisStop = false),
                            StopTimelineInfo(COMPUTER_CENTRE, if (isHeadingGate) StopVisualState.NEXT_STOP else StopVisualState.COMPLETED, if (isHeadingGate) "Approaching" else "Crossed", isAtThisStop = false),
                            StopTimelineInfo(HOSTEL, if (isHeadingGate) StopVisualState.COMPLETED else StopVisualState.NEXT_STOP, if (isHeadingGate) "Crossed" else "Approaching", isAtThisStop = false)
                        )
                        RoutePositionResult(
                            primaryLandmark = COMPUTER_CENTRE,
                            secondaryLandmark = HOSTEL,
                            isAtLandmark = false,
                            isBetween = true,
                            movingDirection = movingDirection,
                            isAtGate = false,
                            routeProgressFloat = 2.7f,
                            driverDetailedLocation = locLabel,
                            driverDirectionSubtitle = subLabel,
                            studentPrimaryText = locLabel,
                            studentSubtitleText = subLabel,
                            timelineStops = stops
                        )
                    } else {
                        val stops = listOf(
                            StopTimelineInfo(GATE, StopVisualState.AT_STOP, "At Gate", isAtThisStop = true),
                            StopTimelineInfo(TRUNKUT, StopVisualState.FUTURE_STOP, "Upcoming", isAtThisStop = false),
                            StopTimelineInfo(COMPUTER_CENTRE, StopVisualState.FUTURE_STOP, "Upcoming", isAtThisStop = false),
                            StopTimelineInfo(HOSTEL, StopVisualState.FUTURE_STOP, "Upcoming", isAtThisStop = false)
                        )
                        RoutePositionResult(
                            primaryLandmark = GATE,
                            secondaryLandmark = null,
                            isAtLandmark = true,
                            isBetween = false,
                            movingDirection = CartDirection.TOWARD_GATE,
                            isAtGate = true,
                            routeProgressFloat = 0.0f,
                            driverDetailedLocation = "At Main Gate",
                            driverDirectionSubtitle = "Arrived at Gate",
                            studentPrimaryText = "At Main Gate",
                            studentSubtitleText = "✓ Arrived at Gate",
                            timelineStops = stops
                        )
                    }
                }
            }
        }

        fun getCartLocationDescription(latitude: Double?, longitude: Double?): String {
            val result = evaluateRoutePosition(latitude, longitude)
            return result?.driverDetailedLocation ?: "Location updating…"
        }

        fun getStudentFacingDriverLocation(latitude: Double?, longitude: Double?): String {
            val result = evaluateRoutePosition(latitude, longitude) ?: return "Driver location updating…"
            return if (result.isAtGate) {
                "Driver has arrived at Gate"
            } else {
                "Driver is ${result.studentPrimaryText} (${result.studentSubtitleText})"
            }
        }
    }
}
