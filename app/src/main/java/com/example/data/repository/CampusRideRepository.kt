package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.api.CampusBackendClient
import com.example.data.api.CreateRideRequest
import com.example.data.api.DutyStatusRequest
import com.example.data.api.LocationUpdateRequest
import com.example.data.model.GolfCartState
import com.example.data.model.GolfCartStatus
import com.example.data.model.RideRequest
import com.example.data.model.RideRequestStatus
import com.example.data.model.ScheduleStatus
import com.example.data.model.UserRole
import com.example.location.GeofenceManager
import com.example.notification.CriticalAlertManager
import com.example.notification.FcmRoleNotificationManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class CampusRideRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("campus_ride_prefs", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(Dispatchers.Default)

    // Current Role State
    private val _currentRole = MutableStateFlow<UserRole?>(getSavedRole())
    val currentRole: StateFlow<UserRole?> = _currentRole.asStateFlow()

    // Dark Mode Theme State
    private val _isDarkMode = MutableStateFlow(
        prefs.getBoolean("pref_dark_mode", false)
    )
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun setDarkMode(enabled: Boolean) {
        prefs.edit().putBoolean("pref_dark_mode", enabled).apply()
        _isDarkMode.value = enabled
    }

    // Driver Availability
    private val _isDriverAvailable = MutableStateFlow(true)
    val isDriverAvailable: StateFlow<Boolean> = _isDriverAvailable.asStateFlow()

    // 1-Hour Automatic Lunch Break State & Countdown Timer
    private val _lunchBreakEndTimeMs = MutableStateFlow(0L)
    val lunchBreakEndTimeMs: StateFlow<Long> = _lunchBreakEndTimeMs.asStateFlow()

    private val _lunchBreakRemainingSeconds = MutableStateFlow(0)
    val lunchBreakRemainingSeconds: StateFlow<Int> = _lunchBreakRemainingSeconds.asStateFlow()

    private var lunchBreakJob: Job? = null

    // Testing / Simulation Toggles
    private val _simulateNearGate = MutableStateFlow(
        prefs.getBoolean("pref_simulate_near_gate", false)
    )
    val simulateNearGate: StateFlow<Boolean> = _simulateNearGate.asStateFlow()

    private val _overrideWorkingHours = MutableStateFlow(
        prefs.getBoolean("pref_override_hours", true) // Default true so user can test anytime
    )
    val overrideWorkingHours: StateFlow<Boolean> = _overrideWorkingHours.asStateFlow()

    // Fleet State from real data source / Firebase
    private val _fleetCarts = MutableStateFlow<List<GolfCartState>>(
        listOf(
            GolfCartState(
                cartId = "cart_1",
                cartName = "Golf Cart 1",
                latitude = 25.2425,
                longitude = 86.9842,
                speedKmH = 15,
                status = GolfCartStatus.MOVING,
                batteryLevel = 92,
                lastUpdatedMillis = System.currentTimeMillis(),
                distanceToGateMeters = 120,
                relativeMovement = "Approaching Main Gate",
                etaMinutes = 2,
                driverStatus = "Available",
                isAvailable = true
            ),
            GolfCartState(
                cartId = "cart_2",
                cartName = "Golf Cart 2",
                latitude = 25.2410,
                longitude = 86.9820,
                speedKmH = 0,
                status = GolfCartStatus.HALTED,
                batteryLevel = 85,
                lastUpdatedMillis = System.currentTimeMillis(),
                distanceToGateMeters = 350,
                relativeMovement = "Halted near Guest House",
                etaMinutes = 5,
                driverStatus = "Available",
                isAvailable = true
            )
        )
    )
    val fleetCarts: StateFlow<List<GolfCartState>> = _fleetCarts.asStateFlow()

    // Active Golf Cart State for current user session
    private val _golfCartState = MutableStateFlow<GolfCartState?>(null)
    val golfCartState: StateFlow<GolfCartState?> = _golfCartState.asStateFlow()

    fun findBestAvailableCart(pickupLocation: String): GolfCartState? {
        val available = _fleetCarts.value.filter { it.isAvailable && it.driverStatus == "Available" }
        if (available.isEmpty()) return null
        return available.minByOrNull { it.etaMinutes ?: Int.MAX_VALUE }
    }

    // Ride Requests
    private val _requests = MutableStateFlow<List<RideRequest>>(emptyList())
    val requests: StateFlow<List<RideRequest>> = _requests.asStateFlow()

    // Active Student Request
    private val _activeStudentRequest = MutableStateFlow<RideRequest?>(null)
    val activeStudentRequest: StateFlow<RideRequest?> = _activeStudentRequest.asStateFlow()

    // Active Faculty Request
    private val _activeFacultyRequest = MutableStateFlow<RideRequest?>(null)
    val activeFacultyRequest: StateFlow<RideRequest?> = _activeFacultyRequest.asStateFlow()

    // Faculty Pickup Locations List
    private val _facultyPickupLocations = MutableStateFlow(
        listOf(
            "Guest House",
            "Academic Block",
            "Computer Science Wing",
            "Student Hostels",
            "Sports Ground",
            "Admin Block"
        )
    )
    val facultyPickupLocations: StateFlow<List<String>> = _facultyPickupLocations.asStateFlow()

    private val _selectedFacultyPickup = MutableStateFlow("Guest House")
    val selectedFacultyPickup: StateFlow<String> = _selectedFacultyPickup.asStateFlow()

    fun setSelectedFacultyPickup(location: String) {
        _selectedFacultyPickup.value = location
    }

    // 5-minute Cooldown Seconds remaining (0 means ready)
    private val _cooldownSeconds = MutableStateFlow(0)
    val cooldownSeconds: StateFlow<Int> = _cooldownSeconds.asStateFlow()

    private var cooldownJob: Job? = null

    private val context: Context = context.applicationContext

    private var driverListenerRegistration: ListenerRegistration? = null
    private var studentListenerRegistration: ListenerRegistration? = null

    init {
        CriticalAlertManager.initNotificationChannel(this.context)
        val savedRole = getSavedRole()
        Log.d("CampusRideRepo", "Init CampusRideRepository: Saved role from preferences = $savedRole")
        if (savedRole != null) {
            _currentRole.value = savedRole
            FcmRoleNotificationManager.syncRoleFcmSubscription(this.context, savedRole)
            if (savedRole == UserRole.DRIVER) {
                Log.d("CampusRideRepo", "Init CampusRideRepository: Saved role is DRIVER. Starting driver Firestore listener...")
                startDriverFirestoreListener()
            }
        }
        checkAndRestoreLunchBreak()
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { result ->
                cont.resume(result)
            }
            addOnFailureListener { exception ->
                cont.resumeWith(Result.failure(exception))
            }
        }

    private suspend fun ensureFirebaseAuth(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                Log.d("CampusRideRepo", "FirebaseApp not initialized. Calling FirebaseApp.initializeApp(context)...")
                FirebaseApp.initializeApp(context)
            } else {
                Log.d("CampusRideRepo", "FirebaseApp already initialized.")
            }

            val app = try { FirebaseApp.getInstance() } catch (e: Exception) { null }
            if (app != null) {
                val opts = app.options
                Log.d("CampusRideRepo", "FirebaseApp Instance: Name=${app.name}, ProjectId=${opts.projectId}, AppId=${opts.applicationId}, ApiKey=${if (opts.apiKey.isNullOrEmpty()) "MISSING" else "PRESENT (${opts.apiKey.take(6)}...)"}")
            } else {
                Log.w("CampusRideRepo", "FirebaseApp.getInstance() returned null or threw exception")
            }

            val auth = FirebaseAuth.getInstance()
            val currentUser = auth.currentUser
            if (currentUser != null) {
                Log.d("CampusRideRepo", "Firebase Auth: User already signed in. uid = ${currentUser.uid}")
                return@withContext Result.success(Unit)
            }

            Log.d("CampusRideRepo", "Firebase Auth: No user signed in. Calling signInAnonymously()...")
            val result = auth.signInAnonymously().awaitTask()
            val user = result.user
            if (user != null) {
                Log.d("CampusRideRepo", "Firebase Auth: Anonymous sign-in success, uid = ${user.uid}")
                Result.success(Unit)
            } else {
                val msg = "Firebase Auth: Anonymous sign-in returned null user"
                Log.e("CampusRideRepo", msg)
                Result.failure(IllegalStateException(msg))
            }
        } catch (e: Exception) {
            val exClass = e.javaClass.name
            val exMsg = e.message ?: "No error message"
            val errorCode = (e as? FirebaseAuthException)?.errorCode
                ?: (e as? FirebaseException)?.message
                ?: "NONE"
            val stackTrace = Log.getStackTraceString(e)

            Log.e("CampusRideRepo", "================ FIREBASE AUTH FAILURE DEBUG LOG ================")
            Log.e("CampusRideRepo", "Exception Class : $exClass")
            Log.e("CampusRideRepo", "Exception Message : $exMsg")
            Log.e("CampusRideRepo", "Firebase Error Code: $errorCode")
            Log.e("CampusRideRepo", "Stack Trace:\n$stackTrace")
            Log.e("CampusRideRepo", "=================================================================")

            val detailedErrorMsg = "Firebase Authentication failed: [$exClass | Code: $errorCode] $exMsg"
            Result.failure(IllegalStateException(detailedErrorMsg, e))
        }
    }

    fun startDriverFirestoreListener() {
        driverListenerRegistration?.remove()

        // Requirement 2: Ensure _currentRole is initialized before listener registration
        val activeRole = _currentRole.value ?: getSavedRole()?.also { restored ->
            _currentRole.value = restored
        }

        Log.d("CampusRideRepo", "Firestore Listener: Registering driver listener for ride_requests. Current role = $activeRole")

        if (activeRole != UserRole.DRIVER) {
            Log.w("CampusRideRepo", "Firestore Listener: Aborting driver listener registration as current role ($activeRole) is not DRIVER.")
            return
        }

        scope.launch(Dispatchers.IO) {
            // Requirement 1: Do not attach addSnapshotListener until anonymous authentication succeeds
            val authRes = ensureFirebaseAuth()
            if (authRes.isFailure) {
                val err = authRes.exceptionOrNull()
                Log.e("CampusRideRepo", "Firestore Listener: Aborting driver listener registration because Firebase Auth failed: ${err?.message}", err)
                return@launch
            }

            withContext(Dispatchers.Main) {
                driverListenerRegistration?.remove()
                try {
                    val firestore = FirebaseFirestore.getInstance()
                    Log.d("CampusRideRepo", "Firestore Listener: Attaching addSnapshotListener on 'ride_requests' collection...")

                    driverListenerRegistration = firestore.collection("ride_requests")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.e("CampusRideRepo", "Firestore Listener Error: Exception listening to ride requests (Code: ${error.code})", error)
                                return@addSnapshotListener
                            }

                            Log.d("CampusRideRepo", "Firestore Snapshot Received: isNull=${snapshot == null}, isEmpty=${snapshot?.isEmpty}")

                            if (snapshot != null && !snapshot.isEmpty) {
                                val docCount = snapshot.documents.size
                                Log.d("CampusRideRepo", "Firestore Snapshot: Received $docCount documents")

                                val incomingList = mutableListOf<RideRequest>()
                                for (doc in snapshot.documents) {
                                    try {
                                        val id = doc.getString("id") ?: doc.id
                                        val requesterTypeStr = doc.getString("requesterType") ?: "STUDENT"
                                        val reqType = if (requesterTypeStr == "FACULTY") com.example.data.model.RequesterType.FACULTY else com.example.data.model.RequesterType.STUDENT
                                        val studentName = doc.getString("studentName") ?: ""
                                        val pickupLocation = doc.getString("pickupLocation") ?: "Main Gate"
                                        val distance = doc.getLong("distanceToGateMeters")?.toInt() ?: 0
                                        val statusStr = doc.getString("status") ?: "PENDING"
                                        val status = try { RideRequestStatus.valueOf(statusStr) } catch (e: Exception) { RideRequestStatus.PENDING }
                                        val cartId = doc.getString("assignedCartId")
                                        val cartName = doc.getString("assignedCartName")
                                        val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()

                                        val request = RideRequest(
                                            id = id,
                                            requesterType = reqType,
                                            studentName = studentName,
                                            pickupLocation = pickupLocation,
                                            distanceToGateMeters = distance,
                                            status = status,
                                            timestamp = timestamp,
                                            assignedCartId = cartId,
                                            assignedCartName = cartName
                                        )
                                        incomingList.add(request)

                                        val currentRoleVal = _currentRole.value
                                        Log.d("CampusRideRepo", "Parsed RideRequest: id=$id, status=$status, student=$studentName, cartId=$cartId, Current role value = $currentRoleVal")

                                        // Trigger Full-Screen Alert on Driver Device for PENDING requests
                                        if (status == RideRequestStatus.PENDING && currentRoleVal == UserRole.DRIVER) {
                                            Log.d("CampusRideRepo", "Triggering critical driver alert for request ${request.id}, status = ${request.status}, currentRole = $currentRoleVal")
                                            CriticalAlertManager.triggerCriticalDriverAlert(context, request, UserRole.DRIVER)
                                        } else {
                                            Log.d("CampusRideRepo", "Not triggering critical alert: status=$status (needs PENDING), currentRole=$currentRoleVal (needs DRIVER)")
                                        }
                                    } catch (e: Exception) {
                                        Log.e("CampusRideRepo", "Error parsing Firestore ride request document", e)
                                    }
                                }
                                if (incomingList.isNotEmpty()) {
                                    _requests.value = incomingList
                                }
                            } else {
                                Log.d("CampusRideRepo", "Firestore Snapshot: Received empty snapshot")
                            }
                        }
                } catch (e: Exception) {
                    Log.e("CampusRideRepo", "Error attaching driver Firestore listener", e)
                }
            }
        }
    }

    fun startStudentRequestListener(requestId: String) {
        studentListenerRegistration?.remove()
        scope.launch(Dispatchers.IO) {
            val authRes = ensureFirebaseAuth()
            if (authRes.isFailure) {
                val err = authRes.exceptionOrNull()
                Log.e("CampusRideRepo", "Student Listener: Aborting because Firebase Auth failed: ${err?.message}", err)
                return@launch
            }
            withContext(Dispatchers.Main) {
                studentListenerRegistration?.remove()
                try {
                    val firestore = FirebaseFirestore.getInstance()
                    Log.d("CampusRideRepo", "Student Listener: Attaching listener for request $requestId...")
                    studentListenerRegistration = firestore.collection("ride_requests")
                        .document(requestId)
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.e("CampusRideRepo", "Student Listener Error: Error listening to request $requestId (Code: ${error.code})", error)
                                return@addSnapshotListener
                            }
                            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener

                            val statusStr = snapshot.getString("status") ?: return@addSnapshotListener
                            val status = try { RideRequestStatus.valueOf(statusStr) } catch (e: Exception) { return@addSnapshotListener }

                            Log.d("CampusRideRepo", "Student Listener Snapshot: Request $requestId status changed to $status")
                            _activeStudentRequest.value = _activeStudentRequest.value?.copy(status = status)
                            _activeFacultyRequest.value = _activeFacultyRequest.value?.copy(status = status)

                            if (status == RideRequestStatus.ACCEPTED) {
                                Log.d("CampusRideRepo", "Student Listener: Ride accepted! Triggering student acceptance notification.")
                                CriticalAlertManager.triggerStudentAcceptanceNotification(context, _currentRole.value)
                            }
                        }
                } catch (e: Exception) {
                    Log.e("CampusRideRepo", "Error attaching student request listener", e)
                }
            }
        }
    }

    private fun checkAndRestoreLunchBreak() {
        val savedEndTime = prefs.getLong("pref_lunch_break_end_time", 0L)
        if (savedEndTime > 0L) {
            val now = System.currentTimeMillis()
            if (now < savedEndTime) {
                val remainingSecs = ((savedEndTime - now) / 1000L).toInt()
                _lunchBreakEndTimeMs.value = savedEndTime
                _lunchBreakRemainingSeconds.value = remainingSecs
                setDriverDutyState("Lunch Break")
                startLunchBreakCountdown(savedEndTime)
            } else {
                clearLunchBreakData()
                setDriverDutyState("Available")
            }
        }
    }

    fun startLunchBreak() {
        val startTime = System.currentTimeMillis()
        val endTime = startTime + 3600_000L
        prefs.edit().putLong("pref_lunch_break_end_time", endTime).apply()
        _lunchBreakEndTimeMs.value = endTime
        _lunchBreakRemainingSeconds.value = 3600
        setDriverDutyState("Lunch Break")
        startLunchBreakCountdown(endTime)
    }

    private fun startLunchBreakCountdown(endTimeMs: Long) {
        lunchBreakJob?.cancel()
        lunchBreakJob = scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val remainingMs = endTimeMs - now
                if (remainingMs <= 0) {
                    clearLunchBreakData()
                    setDriverDutyState("Available")
                    break
                } else {
                    _lunchBreakRemainingSeconds.value = (remainingMs / 1000L).toInt()
                    delay(1000L)
                }
            }
        }
    }

    private fun clearLunchBreakData() {
        prefs.edit().remove("pref_lunch_break_end_time").apply()
        _lunchBreakEndTimeMs.value = 0L
        _lunchBreakRemainingSeconds.value = 0
        lunchBreakJob?.cancel()
        lunchBreakJob = null
    }

    private val _driverDutyState = MutableStateFlow("Available")
    val driverDutyState: StateFlow<String> = _driverDutyState.asStateFlow()

    fun setDriverDutyState(status: String) {
        _driverDutyState.value = status
        val isAvail = (status == "Available")
        _isDriverAvailable.value = isAvail
        _golfCartState.value = _golfCartState.value?.copy(
            driverStatus = status,
            isAvailable = isAvail,
            status = when(status) {
                "Available" -> GolfCartStatus.HALTED
                "On Trip", "Assigned" -> GolfCartStatus.MOVING
                else -> GolfCartStatus.OFFLINE
            }
        )
    }

    fun verifyFacultyAccessCode(enteredCode: String): Boolean {
        val validCode = prefs.getString("remote_faculty_code", "IIITFAC2026") ?: "IIITFAC2026"
        return enteredCode.trim().equals(validCode, ignoreCase = true)
    }

    fun verifyDriverAccessCode(enteredCode: String): Boolean {
        val validCode = prefs.getString("remote_driver_code", "IIITDRV2026") ?: "IIITDRV2026"
        return enteredCode.trim().equals(validCode, ignoreCase = true)
    }

    fun updateFacultyAccessCode(newCode: String) {
        prefs.edit().putString("remote_faculty_code", newCode.trim()).apply()
    }

    fun updateDriverAccessCode(newCode: String) {
        prefs.edit().putString("remote_driver_code", newCode.trim()).apply()
    }

    private fun getSavedRole(): UserRole? {
        val saved = prefs.getString("saved_user_role", null)
        return UserRole.fromString(saved)
    }

    fun saveRole(role: UserRole) {
        prefs.edit().putString("saved_user_role", role.name).apply()
        _currentRole.value = role
        FcmRoleNotificationManager.syncRoleFcmSubscription(context, role)
        if (role == UserRole.DRIVER) {
            startDriverFirestoreListener()
        } else {
            driverListenerRegistration?.remove()
        }
    }

    fun clearRole() {
        prefs.edit().remove("saved_user_role").apply()
        _currentRole.value = null
        driverListenerRegistration?.remove()
        studentListenerRegistration?.remove()
    }

    fun setDriverAvailable(available: Boolean) {
        _isDriverAvailable.value = available
        _golfCartState.value = _golfCartState.value?.copy(
            status = if (!available) GolfCartStatus.OFFLINE else GolfCartStatus.HALTED
        )
    }

    fun updateDriverLiveLocation(
        cartId: String = "cart_1",
        latitude: Double,
        longitude: Double,
        speedKmH: Int = 0,
        bearing: Float = 0f,
        targetLat: Double = GeofenceManager.GATE_LAT,
        targetLng: Double = GeofenceManager.GATE_LNG
    ) {
        val currentCarts = _fleetCarts.value
        val existingCart = currentCarts.find { it.cartId == cartId } ?: currentCarts.firstOrNull()

        val prevLat = existingCart?.latitude
        val prevLng = existingCart?.longitude

        val currentDistMeters = GeofenceManager.calculateDistanceMeters(latitude, longitude, targetLat, targetLng).roundToInt()

        val relativeMovementStr = if (prevLat != null && prevLng != null) {
            val prevDistMeters = GeofenceManager.calculateDistanceMeters(prevLat, prevLng, targetLat, targetLng).roundToInt()
            val diff = prevDistMeters - currentDistMeters
            when {
                diff > 3 -> "Coming Towards You"
                diff < -3 -> "Moving Away"
                else -> "Stationary"
            }
        } else {
            "Stationary"
        }

        val calculatedEta = if (speedKmH >= 2) {
            val speedMps = (speedKmH * 1000.0) / 3600.0
            val etaSecs = currentDistMeters / speedMps
            maxOf(1, (etaSecs / 60.0).roundToInt())
        } else {
            null
        }

        val updatedCart = (existingCart ?: GolfCartState(cartId = cartId, cartName = "Golf Cart 1")).copy(
            latitude = latitude,
            longitude = longitude,
            speedKmH = speedKmH,
            bearing = bearing,
            status = if (speedKmH > 0) GolfCartStatus.MOVING else GolfCartStatus.HALTED,
            lastUpdatedMillis = System.currentTimeMillis(),
            distanceToGateMeters = GeofenceManager.calculateDistanceMeters(latitude, longitude, GeofenceManager.GATE_LAT, GeofenceManager.GATE_LNG).roundToInt(),
            distanceToUserMeters = currentDistMeters,
            relativeMovement = relativeMovementStr,
            etaMinutes = calculatedEta
        )

        _fleetCarts.value = currentCarts.map { if (it.cartId == cartId) updatedCart else it }
        if (_golfCartState.value?.cartId == cartId || _golfCartState.value == null) {
            _golfCartState.value = updatedCart
        }
    }

    fun toggleSimulateNearGate(enabled: Boolean) {
        _simulateNearGate.value = enabled
        prefs.edit().putBoolean("pref_simulate_near_gate", enabled).apply()
    }

    fun toggleOverrideHours(enabled: Boolean) {
        _overrideWorkingHours.value = enabled
        prefs.edit().putBoolean("pref_override_hours", enabled).apply()
    }

    suspend fun sendStudentRideRequest(
        studentLat: Double,
        studentLng: Double,
        studentName: String = ""
    ): Result<RideRequest> = withContext(Dispatchers.IO) {
        // Anti-spam duplicate request protection
        val currentActive = _activeStudentRequest.value
        if (currentActive != null && (currentActive.status == RideRequestStatus.PENDING || currentActive.status == RideRequestStatus.ACCEPTED)) {
            return@withContext Result.failure(IllegalStateException("Anti-spam protection: You already have an active ride request in progress."))
        }

        val calculatedDistance = GeofenceManager.calculateDistanceMeters(studentLat, studentLng)
        if (GeofenceManager.isTestModeEnabled) {
            Log.d("CampusRideRepo", "TEST MODE: Geofence bypassed. Request allowed.")
        } else if (calculatedDistance > GeofenceManager.MAX_GEOFENCE_METERS) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Ride request rejected by backend: You are outside the 70-meter radius of IIIT Bhagalpur Main Gate (${calculatedDistance.roundToInt()}m from gate)."
                )
            )
        }

        val schedule = ScheduleStatus.getCurrentStatus(_overrideWorkingHours.value)
        if (!schedule.isAvailable) {
            return@withContext Result.failure(IllegalStateException(schedule.message))
        }

        if (!_isDriverAvailable.value || _driverDutyState.value == "Lunch Break") {
            return@withContext Result.failure(IllegalStateException("All golf carts are temporarily unavailable."))
        }

        if (_cooldownSeconds.value > 0) {
            return@withContext Result.failure(IllegalStateException("Please wait for cooldown timer before requesting again."))
        }

        val assignedCart = findBestAvailableCart("Main Gate")
            ?: return@withContext Result.failure(IllegalStateException("All golf carts are temporarily unavailable."))

        val request = RideRequest(
            requesterType = com.example.data.model.RequesterType.STUDENT,
            studentName = studentName,
            pickupLocation = "Main Gate",
            distanceToGateMeters = calculatedDistance.roundToInt(),
            status = RideRequestStatus.PENDING,
            assignedCartId = assignedCart.cartId,
            assignedCartName = assignedCart.cartName
        )

        try {
            val authRes = ensureFirebaseAuth()
            if (authRes.isFailure) {
                val err = authRes.exceptionOrNull() ?: IllegalStateException("Firebase Authentication failed.")
                Log.e("CampusRideRepo", "sendStudentRideRequest: Firebase Authentication failed", err)
                return@withContext Result.failure(err)
            }
            val firestore = FirebaseFirestore.getInstance()

            val docData = mapOf(
                "id" to request.id,
                "requesterType" to request.requesterType.name,
                "studentName" to request.studentName,
                "pickupLocation" to request.pickupLocation,
                "distanceToGateMeters" to request.distanceToGateMeters,
                "status" to request.status.name,
                "assignedCartId" to request.assignedCartId,
                "assignedCartName" to request.assignedCartName,
                "timestamp" to request.timestamp
            )

            firestore.collection("ride_requests")
                .document(request.id)
                .set(docData)
                .awaitTask()

            var driverFcmToken: String? = null
            try {
                val driverSnap = firestore.collection("drivers")
                    .document(assignedCart.cartId ?: "cart_1")
                    .get()
                    .awaitTask()
                driverFcmToken = driverSnap.getString("fcmToken")
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "Driver FCM token query optional fallback", e)
            }

            val dispatchData = mapOf(
                "id" to "dispatch_${request.id}",
                "requestId" to request.id,
                "requesterType" to request.requesterType.name,
                "pickupLocation" to request.pickupLocation,
                "studentName" to request.studentName,
                "distanceToGateMeters" to request.distanceToGateMeters,
                "assignedCartId" to request.assignedCartId,
                "targetFcmToken" to (driverFcmToken ?: ""),
                "targetTopic" to "drivers",
                "type" to "RIDE_REQUEST",
                "status" to "SENT",
                "timestamp" to System.currentTimeMillis()
            )

            firestore.collection("fcm_dispatches")
                .document("dispatch_${request.id}")
                .set(dispatchData)
                .awaitTask()

            _golfCartState.value = assignedCart
            _activeStudentRequest.value = request
            val updated = listOf(request) + _requests.value
            _requests.value = updated

            startStudentRequestListener(request.id)
            FcmRoleNotificationManager.dispatchDriverPush(request)

            // Asynchronously sync to Render backend
            scope.launch(Dispatchers.IO) {
                try {
                    Log.d("NETWORK_TRACE", "Calling createRideRequest()")
                    CampusBackendClient.api.createRideRequest(
                        CreateRideRequest(
                            requesterType = request.requesterType.name,
                            studentName = request.studentName,
                            pickupLocation = request.pickupLocation,
                            distanceToGateMeters = request.distanceToGateMeters,
                            assignedCartId = request.assignedCartId
                        )
                    )
                } catch (e: Exception) {
                    Log.e("NETWORK_TRACE", "Retrofit failed", e)
                    Log.w("CampusRideRepo", "Render backend sync notice: ${e.message}")
                }
            }

            startCooldownTimer(300)

            Result.success(request)
        } catch (e: Exception) {
            Log.e("CampusRideRepo", "Firestore / FCM write failed", e)
            Result.failure(Exception("Failed to dispatch request via Firebase Firestore/FCM: ${e.localizedMessage ?: e.message}"))
        }
    }

    suspend fun sendFacultyRideRequest(pickupLocation: String): Result<RideRequest> = withContext(Dispatchers.IO) {
        val currentActive = _activeFacultyRequest.value
        if (currentActive != null && (currentActive.status == RideRequestStatus.PENDING || currentActive.status == RideRequestStatus.ACCEPTED)) {
            return@withContext Result.failure(IllegalStateException("Anti-spam protection: You already have an active faculty ride request in progress."))
        }

        if (!_isDriverAvailable.value || _driverDutyState.value == "Lunch Break") {
            return@withContext Result.failure(IllegalStateException("All golf carts are temporarily unavailable."))
        }

        val assignedCart = findBestAvailableCart(pickupLocation)
            ?: return@withContext Result.failure(IllegalStateException("All golf carts are temporarily unavailable."))

        val request = RideRequest(
            requesterType = com.example.data.model.RequesterType.FACULTY,
            studentName = "",
            pickupLocation = pickupLocation,
            status = RideRequestStatus.PENDING,
            assignedCartId = assignedCart.cartId,
            assignedCartName = assignedCart.cartName
        )

        try {
            val authRes = ensureFirebaseAuth()
            if (authRes.isFailure) {
                val err = authRes.exceptionOrNull() ?: IllegalStateException("Firebase Authentication failed.")
                Log.e("CampusRideRepo", "sendFacultyRideRequest: Firebase Authentication failed", err)
                return@withContext Result.failure(err)
            }
            val firestore = FirebaseFirestore.getInstance()

            val docData = mapOf(
                "id" to request.id,
                "requesterType" to request.requesterType.name,
                "studentName" to request.studentName,
                "pickupLocation" to request.pickupLocation,
                "status" to request.status.name,
                "assignedCartId" to request.assignedCartId,
                "assignedCartName" to request.assignedCartName,
                "timestamp" to request.timestamp
            )

            firestore.collection("ride_requests")
                .document(request.id)
                .set(docData)
                .awaitTask()

            var driverFcmToken: String? = null
            try {
                val driverSnap = firestore.collection("drivers")
                    .document(assignedCart.cartId ?: "cart_1")
                    .get()
                    .awaitTask()
                driverFcmToken = driverSnap.getString("fcmToken")
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "Driver FCM token query optional fallback", e)
            }

            val dispatchData = mapOf(
                "id" to "dispatch_${request.id}",
                "requestId" to request.id,
                "requesterType" to request.requesterType.name,
                "pickupLocation" to request.pickupLocation,
                "assignedCartId" to request.assignedCartId,
                "targetFcmToken" to (driverFcmToken ?: ""),
                "targetTopic" to "drivers",
                "type" to "RIDE_REQUEST",
                "status" to "SENT",
                "timestamp" to System.currentTimeMillis()
            )

            firestore.collection("fcm_dispatches")
                .document("dispatch_${request.id}")
                .set(dispatchData)
                .awaitTask()

            _golfCartState.value = assignedCart
            _activeFacultyRequest.value = request
            val updated = listOf(request) + _requests.value
            _requests.value = updated

            startStudentRequestListener(request.id)
            FcmRoleNotificationManager.dispatchDriverPush(request)

            scope.launch(Dispatchers.IO) {
                try {
                    Log.d("NETWORK_TRACE", "Calling createRideRequest()")
                    CampusBackendClient.api.createRideRequest(
                        CreateRideRequest(
                            requesterType = request.requesterType.name,
                            studentName = request.studentName,
                            pickupLocation = request.pickupLocation,
                            distanceToGateMeters = request.distanceToGateMeters,
                            assignedCartId = request.assignedCartId
                        )
                    )
                } catch (e: Exception) {
                    Log.e("NETWORK_TRACE", "Retrofit failed", e)
                    Log.w("CampusRideRepo", "Render backend sync notice: ${e.message}")
                }
            }

            Result.success(request)
        } catch (e: Exception) {
            Log.e("CampusRideRepo", "Firestore / FCM write failed", e)
            Result.failure(Exception("Failed to dispatch request via Firebase Firestore/FCM: ${e.localizedMessage ?: e.message}"))
        }
    }

    fun acceptRideRequest(requestId: String) {
        CriticalAlertManager.stopAlert(context)

        val targetReq = _requests.value.find { it.id == requestId }
        targetReq?.assignedCartId?.let { cartId ->
            _fleetCarts.value = _fleetCarts.value.map {
                if (it.cartId == cartId) it.copy(isAvailable = false, activeRequestId = requestId, driverStatus = "Occupied")
                else it
            }
        }

        val updatedList = _requests.value.map { req ->
            if (req.id == requestId) req.copy(status = RideRequestStatus.ACCEPTED) else req
        }
        _requests.value = updatedList

        if (_activeStudentRequest.value?.id == requestId) {
            _activeStudentRequest.value = _activeStudentRequest.value?.copy(status = RideRequestStatus.ACCEPTED)
        }
        if (_activeFacultyRequest.value?.id == requestId) {
            _activeFacultyRequest.value = _activeFacultyRequest.value?.copy(status = RideRequestStatus.ACCEPTED)
        }

        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("ride_requests")
                    .document(requestId)
                    .update("status", RideRequestStatus.ACCEPTED.name)

                firestore.collection("drivers")
                    .document("cart_1")
                    .update(mapOf("isAvailable" to false, "driverStatus" to "Occupied"))
            } catch (e: Exception) {
                Log.e("CampusRideRepo", "Failed to update ACCEPTED status in Firestore", e)
            }
            try {
                Log.d("NETWORK_TRACE", "Calling acceptRide()")
                CampusBackendClient.api.acceptRide(requestId)
            } catch (e: Exception) {
                Log.e("NETWORK_TRACE", "Retrofit failed", e)
                Log.w("CampusRideRepo", "Render accept backend sync notice: ${e.message}")
            }
        }

        targetReq?.let {
            FcmRoleNotificationManager.dispatchStudentAcceptancePush(it)
        }
        CriticalAlertManager.triggerStudentAcceptanceNotification(context, _currentRole.value)
    }

    fun declineRideRequest(requestId: String) {
        CriticalAlertManager.stopAlert(context)

        val targetReq = _requests.value.find { it.id == requestId }
        targetReq?.assignedCartId?.let { cartId ->
            _fleetCarts.value = _fleetCarts.value.map {
                if (it.cartId == cartId) it.copy(isAvailable = true, activeRequestId = null, driverStatus = "Available")
                else it
            }
        }

        val updatedList = _requests.value.map { req ->
            if (req.id == requestId) req.copy(status = RideRequestStatus.REJECTED) else req
        }
        _requests.value = updatedList

        if (_activeStudentRequest.value?.id == requestId) {
            _activeStudentRequest.value = _activeStudentRequest.value?.copy(status = RideRequestStatus.REJECTED)
        }
        if (_activeFacultyRequest.value?.id == requestId) {
            _activeFacultyRequest.value = _activeFacultyRequest.value?.copy(status = RideRequestStatus.REJECTED)
        }

        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("ride_requests")
                    .document(requestId)
                    .update("status", RideRequestStatus.REJECTED.name)

                firestore.collection("drivers")
                    .document("cart_1")
                    .update(mapOf("isAvailable" to true, "driverStatus" to "Available"))
            } catch (e: Exception) {
                Log.e("CampusRideRepo", "Failed to update REJECTED status in Firestore", e)
            }
            try {
                Log.d("NETWORK_TRACE", "Calling declineRide()")
                CampusBackendClient.api.declineRide(requestId)
            } catch (e: Exception) {
                Log.e("NETWORK_TRACE", "Retrofit failed", e)
                Log.w("CampusRideRepo", "Render decline backend sync notice: ${e.message}")
            }
        }
    }

    fun completeRideRequest(requestId: String) {
        val targetReq = _requests.value.find { it.id == requestId }
        targetReq?.assignedCartId?.let { cartId ->
            _fleetCarts.value = _fleetCarts.value.map {
                if (it.cartId == cartId) it.copy(isAvailable = true, activeRequestId = null, driverStatus = "Available")
                else it
            }
        }

        val updatedList = _requests.value.map { req ->
            if (req.id == requestId) req.copy(status = RideRequestStatus.COMPLETED) else req
        }
        _requests.value = updatedList

        if (_activeStudentRequest.value?.id == requestId) {
            _activeStudentRequest.value = _activeStudentRequest.value?.copy(status = RideRequestStatus.COMPLETED)
        }
        if (_activeFacultyRequest.value?.id == requestId) {
            _activeFacultyRequest.value = _activeFacultyRequest.value?.copy(status = RideRequestStatus.COMPLETED)
        }

        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("ride_requests")
                    .document(requestId)
                    .update("status", RideRequestStatus.COMPLETED.name)

                firestore.collection("drivers")
                    .document("cart_1")
                    .update(mapOf("isAvailable" to true, "driverStatus" to "Available"))
            } catch (e: Exception) {
                Log.e("CampusRideRepo", "Failed to update COMPLETED status in Firestore", e)
            }
            try {
                Log.d("NETWORK_TRACE", "Calling completeRide()")
                CampusBackendClient.api.completeRide(requestId)
            } catch (e: Exception) {
                Log.e("NETWORK_TRACE", "Retrofit failed", e)
                Log.w("CampusRideRepo", "Render complete backend sync notice: ${e.message}")
            }
        }
    }

    private fun startCooldownTimer(seconds: Int) {
        cooldownJob?.cancel()
        _cooldownSeconds.value = seconds
        cooldownJob = scope.launch {
            while (_cooldownSeconds.value > 0) {
                delay(1000)
                _cooldownSeconds.value -= 1
            }
        }
    }

    fun setGolfCartEnabled(cartId: String, enabled: Boolean) {
        _fleetCarts.value = _fleetCarts.value.map {
            if (it.cartId == cartId) {
                it.copy(isAvailable = enabled, driverStatus = if (enabled) "Available" else "Offline")
            } else it
        }
    }

    fun getOperationalAnalytics(): Map<String, Any> {
        val total = _requests.value.size
        val accepted = _requests.value.count { it.status == RideRequestStatus.ACCEPTED || it.status == RideRequestStatus.COMPLETED }
        val rejected = _requests.value.count { it.status == RideRequestStatus.REJECTED }
        val pending = _requests.value.count { it.status == RideRequestStatus.PENDING }

        return mapOf(
            "totalRequests" to total,
            "acceptedRequests" to accepted,
            "rejectedRequests" to rejected,
            "pendingRequests" to pending,
            "activeFleetCount" to _fleetCarts.value.count { it.isAvailable },
            "geofenceRadiusMeters" to GeofenceManager.MAX_GEOFENCE_METERS,
            "gateCoordinates" to "${GeofenceManager.GATE_LAT}, ${GeofenceManager.GATE_LNG}"
        )
    }
}
