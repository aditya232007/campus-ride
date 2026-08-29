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
import com.example.data.model.PickupLocation
import com.example.data.model.RideRequest
import com.example.data.model.RideRequestStatus
import com.example.data.model.ScheduleStatus
import com.example.data.model.UserRole
import com.example.location.GeofenceManager
import com.example.notification.CriticalAlertManager
import com.example.notification.FcmRoleNotificationManager
import com.example.util.CampusTimeUtils
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class CampusRideRepository(context: Context) {

    init {
        instance = this
    }

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

    // Driver Selection & Active Trip State
    private val _selectedDriverCartId = MutableStateFlow(
        prefs.getString("pref_selected_driver_cart", "cart_1") ?: "cart_1"
    )
    val selectedDriverCartId: StateFlow<String> = _selectedDriverCartId.asStateFlow()

    private val _driverSessionId = MutableStateFlow(
        prefs.getString("pref_driver_session_id", null) ?: run {
            val newSession = "session_${System.currentTimeMillis()}_${(1000..9999).random()}"
            prefs.edit().putString("pref_driver_session_id", newSession).apply()
            newSession
        }
    )
    val driverSessionId: StateFlow<String> = _driverSessionId.asStateFlow()

    fun setSelectedDriverCartId(cartId: String) {
        _selectedDriverCartId.value = cartId
        prefs.edit().putString("pref_selected_driver_cart", cartId).apply()
    }

    private val _isTripActive = MutableStateFlow(
        prefs.getBoolean("pref_is_trip_active", false)
    )
    val isTripActive: StateFlow<Boolean> = _isTripActive.asStateFlow()

    // Driver Availability
    private val _isDriverAvailable = MutableStateFlow(true)
    val isDriverAvailable: StateFlow<Boolean> = _isDriverAvailable.asStateFlow()

    // Driver Geofence & Location State
    private val _driverLatitude = MutableStateFlow<Double?>(null)
    val driverLatitude: StateFlow<Double?> = _driverLatitude.asStateFlow()

    private val _driverLongitude = MutableStateFlow<Double?>(null)
    val driverLongitude: StateFlow<Double?> = _driverLongitude.asStateFlow()

    private val _distanceToLibraryMeters = MutableStateFlow<Double?>(null)
    val distanceToLibraryMeters: StateFlow<Double?> = _distanceToLibraryMeters.asStateFlow()

    private val _isInsideGeofence = MutableStateFlow(false)
    val isInsideGeofence: StateFlow<Boolean> = _isInsideGeofence.asStateFlow()

    private val _isInsideCampus = MutableStateFlow(false)
    val isInsideCampus: StateFlow<Boolean> = _isInsideCampus.asStateFlow()

    private val _driverGpsAccuracyMeters = MutableStateFlow<Float?>(null)
    val driverGpsAccuracyMeters: StateFlow<Float?> = _driverGpsAccuracyMeters.asStateFlow()

    private val _hasGpsLocation = MutableStateFlow(false)
    val hasGpsLocation: StateFlow<Boolean> = _hasGpsLocation.asStateFlow()

    // 1-Hour Automatic Lunch Break State & Countdown Timer
    private val _lunchBreakEndTimeMs = MutableStateFlow(0L)
    val lunchBreakEndTimeMs: StateFlow<Long> = _lunchBreakEndTimeMs.asStateFlow()

    private val _lunchBreakRemainingSeconds = MutableStateFlow(0)
    val lunchBreakRemainingSeconds: StateFlow<Int> = _lunchBreakRemainingSeconds.asStateFlow()

    // Lunch Break Once-Per-Day Date Tracking (Asia/Kolkata timezone: yyyy-MM-dd)
    private val _lunchBreakUsedDate = MutableStateFlow<String?>(
        prefs.getString("pref_lunch_break_used_date", null)
    )
    val lunchBreakUsedDate: StateFlow<String?> = _lunchBreakUsedDate.asStateFlow()

    private val _isLunchBreakUsedToday = MutableStateFlow(
        CampusTimeUtils.isTodayInCampusTimezone(prefs.getString("pref_lunch_break_used_date", null))
    )
    val isLunchBreakUsedToday: StateFlow<Boolean> = _isLunchBreakUsedToday.asStateFlow()

    private val lunchBreakMutex = Mutex()
    private var lunchBreakListenerRegistration: ListenerRegistration? = null
    private var lunchBreakJob: Job? = null

    // Working Hours Override for campus testing
    private val _overrideWorkingHours = MutableStateFlow(
        prefs.getBoolean("pref_override_hours", true) // Default true so user can test anytime
    )
    val overrideWorkingHours: StateFlow<Boolean> = _overrideWorkingHours.asStateFlow()

    // Dedicated Independent State for Cart 1 and Cart 2
    private val _cart1State = MutableStateFlow(
        GolfCartState(
            cartId = "cart_1",
            cartName = "Cart 1",
            status = GolfCartStatus.OFFLINE,
            isAvailable = true,
            driverStatus = "Offline",
            currentStop = "Main Gate",
            direction = "Main Gate → Boys Hostel"
        )
    )
    val cart1State: StateFlow<GolfCartState> = _cart1State.asStateFlow()

    private val _cart2State = MutableStateFlow(
        GolfCartState(
            cartId = "cart_2",
            cartName = "Cart 2",
            status = GolfCartStatus.OFFLINE,
            isAvailable = true,
            driverStatus = "Offline",
            currentStop = "Boys Hostel",
            direction = "Boys Hostel → Main Gate"
        )
    )
    val cart2State: StateFlow<GolfCartState> = _cart2State.asStateFlow()

    // Fleet State from real data source / Firebase
    private val _fleetCarts = MutableStateFlow<List<GolfCartState>>(
        listOf(_cart1State.value, _cart2State.value)
    )
    val fleetCarts: StateFlow<List<GolfCartState>> = _fleetCarts.asStateFlow()

    // Active Golf Cart State for current user session (aliases selected cart)
    private val _golfCartState = MutableStateFlow<GolfCartState?>(_cart1State.value)
    val golfCartState: StateFlow<GolfCartState?> = _golfCartState.asStateFlow()

    fun findBestAvailableCart(pickupLocation: String): GolfCartState? {
        val available = _fleetCarts.value.filter {
            (it.isLive || (it.isAvailable && !it.driverStatus.equals("Offline", ignoreCase = true))) &&
            !it.driverStatus.equals("Lunch Break", ignoreCase = true) &&
            !it.driverStatus.equals("Occupied", ignoreCase = true)
        }
        if (available.isEmpty()) return null
        return available.minByOrNull { it.etaMinutes ?: Int.MAX_VALUE } ?: available.firstOrNull()
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

    // Faculty Pickup Locations List (Strictly 5 Faculty Locations)
    private val _facultyPickupLocations = MutableStateFlow(
        PickupLocation.FACULTY_LOCATIONS.map { it.displayName }
    )
    val facultyPickupLocations: StateFlow<List<String>> = _facultyPickupLocations.asStateFlow()

    private val _selectedFacultyPickup = MutableStateFlow(PickupLocation.HOSTEL.displayName)
    val selectedFacultyPickup: StateFlow<String> = _selectedFacultyPickup.asStateFlow()

    fun setSelectedFacultyPickup(location: String) {
        _selectedFacultyPickup.value = location
    }

    // 5-minute Cooldown Seconds remaining (0 means ready)
    private val _cooldownSeconds = MutableStateFlow(0)
    val cooldownSeconds: StateFlow<Int> = _cooldownSeconds.asStateFlow()

    private var cooldownJob: Job? = null

    private val context: Context = context.applicationContext

    private val requestCreationMutex = kotlinx.coroutines.sync.Mutex()

    private var driverListenerRegistration: ListenerRegistration? = null
    private var studentListenerRegistration: ListenerRegistration? = null
    private var cart1ListenerRegistration: ListenerRegistration? = null
    private var cart2ListenerRegistration: ListenerRegistration? = null

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
        startGolfCartLiveTrackingListener()
        checkAndRestoreLunchBreak()
        startLunchBreakSyncListener()
        startDailyResetTicker()
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
                                        val studentsWaiting = doc.getLong("studentsWaiting")?.toInt()?.coerceIn(1, 10) ?: 1

                                        val request = RideRequest(
                                            id = id,
                                            requesterType = reqType,
                                            studentName = studentName,
                                            pickupLocation = pickupLocation,
                                            distanceToGateMeters = distance,
                                            status = status,
                                            timestamp = timestamp,
                                            studentsWaiting = studentsWaiting,
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

                                val activeAlert = CriticalAlertManager.activeAlertRequest.value
                                if (activeAlert != null) {
                                    val currentInList = incomingList.find { it.id == activeAlert.id }
                                    if (currentInList == null || currentInList.status != RideRequestStatus.PENDING) {
                                        Log.d("CampusRideRepo", "Active alert request ${activeAlert.id} is no longer PENDING in Firestore snapshot. Stopping alert.")
                                        CriticalAlertManager.stopAlert(context, reason = "CANCELLED / STATUS CHANGED")
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

                            val pickupLocStr = snapshot.getString("pickupLocation") ?: "GATE"
                            val pickupLoc = PickupLocation.fromId(pickupLocStr)

                            val driverLat = snapshot.getDouble("driverLat")
                            val driverLng = snapshot.getDouble("driverLng")
                            val driverBearing = snapshot.getDouble("driverBearing")?.toFloat() ?: 0f

                            val previousStatus = _activeStudentRequest.value?.status

                            val updatedRequest = _activeStudentRequest.value?.copy(
                                status = status,
                                pickupLocation = pickupLoc.id,
                                driverLat = driverLat,
                                driverLng = driverLng,
                                driverBearing = driverBearing
                            )
                            _activeStudentRequest.value = updatedRequest
                            _activeFacultyRequest.value = _activeFacultyRequest.value?.copy(status = status)

                            if (status == RideRequestStatus.ACCEPTED) {
                                if (previousStatus != RideRequestStatus.ACCEPTED) {
                                    Log.d("CampusRideRepo", "Student Listener: Ride accepted! Triggering student acceptance notification.")
                                    CriticalAlertManager.triggerStudentAcceptanceNotification(context, _currentRole.value)
                                }
                            }
                        }
                } catch (e: Exception) {
                    Log.e("CampusRideRepo", "Error attaching student request listener", e)
                }
            }
        }
    }

    fun checkAndRestoreLunchBreak() {
        val today = CampusTimeUtils.getTodayCampusDate()
        val savedUsedDate = prefs.getString("pref_lunch_break_used_date", null)
        _lunchBreakUsedDate.value = savedUsedDate
        val isUsedToday = (savedUsedDate == today)
        _isLunchBreakUsedToday.value = isUsedToday

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
                clearActiveLunchBreakCountdownOnly()
                if (_driverDutyState.value == "Lunch Break") {
                    setDriverDutyState("Available")
                }
            }
        }
    }

    suspend fun startLunchBreak(): Result<Boolean> = withContext(Dispatchers.IO) {
        lunchBreakMutex.withLock {
            val today = CampusTimeUtils.getTodayCampusDate()
            val localUsedDate = prefs.getString("pref_lunch_break_used_date", null)
            if (localUsedDate == today) {
                Log.w("CampusRideRepo", "Lunch break already consumed for today ($today). Second attempt blocked.")
                return@withLock Result.failure(IllegalStateException("Today's lunch break has already been used."))
            }

            val activeAcceptedReq = _requests.value.find { it.status == RideRequestStatus.ACCEPTED }
            if (activeAcceptedReq != null) {
                return@withLock Result.failure(IllegalStateException("Cannot start lunch break while on an active ride."))
            }

            val now = System.currentTimeMillis()
            val endTime = now + 3600_000L
            val activeCartId = _selectedDriverCartId.value

            // Concurrency Protection: Atomic Firestore Transaction
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()
                val docRef = firestore.collection("campus_config").document("lunch_break")

                firestore.runTransaction { transaction ->
                    val snapshot = transaction.get(docRef)
                    if (snapshot.exists()) {
                        val serverUsedDate = snapshot.getString("lastUsedDate")
                        if (serverUsedDate == today) {
                            throw IllegalStateException("Today's lunch break has already been used.")
                        }
                    }
                    val data = mapOf(
                        "lastUsedDate" to today,
                        "activatedTimestamp" to now,
                        "endTimeMs" to endTime,
                        "activatedByCartId" to activeCartId,
                        "activatedAt" to FieldValue.serverTimestamp()
                    )
                    transaction.set(docRef, data, SetOptions.merge())
                }.awaitTask()
            } catch (e: Exception) {
                Log.e("CampusRideRepo", "Atomic lunch break transaction error: ${e.message}", e)
                if (e.message?.contains("already been used") == true) {
                    prefs.edit().putString("pref_lunch_break_used_date", today).apply()
                    _lunchBreakUsedDate.value = today
                    _isLunchBreakUsedToday.value = true
                    return@withLock Result.failure(e)
                }
            }

            // Persist locally for immediate offline/restart persistence
            prefs.edit()
                .putString("pref_lunch_break_used_date", today)
                .putLong("pref_lunch_break_end_time", endTime)
                .apply()

            _lunchBreakUsedDate.value = today
            _isLunchBreakUsedToday.value = true
            _lunchBreakEndTimeMs.value = endTime
            _lunchBreakRemainingSeconds.value = 3600

            withContext(Dispatchers.Main) {
                setDriverDutyState("Lunch Break")
            }
            startLunchBreakCountdown(endTime)

            // Update driver cart status in Firestore
            try {
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("drivers").document(activeCartId).set(
                    mapOf(
                        "driverStatus" to "Lunch Break",
                        "isAvailable" to false,
                        "onDuty" to false,
                        "lastUpdatedMillis" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                )
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "Error updating driver lunch status doc: ${e.message}")
            }

            Log.d("CampusRideRepo", "LUNCH BREAK ACTIVATED: Successfully recorded for date $today")
            Result.success(true)
        }
    }

    fun triggerLunchBreak(onComplete: (Result<Boolean>) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val result = startLunchBreak()
            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }

    private fun startLunchBreakCountdown(endTimeMs: Long) {
        lunchBreakJob?.cancel()
        lunchBreakJob = scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val remainingMs = endTimeMs - now
                if (remainingMs <= 0) {
                    clearActiveLunchBreakCountdownOnly()
                    setDriverDutyState("Available")
                    break
                } else {
                    _lunchBreakRemainingSeconds.value = (remainingMs / 1000L).toInt()
                    delay(1000L)
                }
            }
        }
    }

    private fun clearActiveLunchBreakCountdownOnly() {
        prefs.edit().remove("pref_lunch_break_end_time").apply()
        _lunchBreakEndTimeMs.value = 0L
        _lunchBreakRemainingSeconds.value = 0
        lunchBreakJob?.cancel()
        lunchBreakJob = null
    }

    private fun startLunchBreakSyncListener() {
        lunchBreakListenerRegistration?.remove()
        scope.launch(Dispatchers.IO) {
            val authRes = ensureFirebaseAuth()
            if (authRes.isFailure) return@launch
            withContext(Dispatchers.Main) {
                try {
                    val firestore = FirebaseFirestore.getInstance()
                    lunchBreakListenerRegistration = firestore.collection("campus_config")
                        .document("lunch_break")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                            val cloudUsedDate = snapshot.getString("lastUsedDate")
                            val today = CampusTimeUtils.getTodayCampusDate()
                            if (!cloudUsedDate.isNullOrBlank()) {
                                if (cloudUsedDate == today) {
                                    prefs.edit().putString("pref_lunch_break_used_date", cloudUsedDate).apply()
                                    _lunchBreakUsedDate.value = cloudUsedDate
                                    _isLunchBreakUsedToday.value = true

                                    val activeEndTime = snapshot.getLong("endTimeMs") ?: 0L
                                    val now = System.currentTimeMillis()
                                    if (activeEndTime > now && _lunchBreakEndTimeMs.value == 0L) {
                                        val remainingSecs = ((activeEndTime - now) / 1000L).toInt()
                                        _lunchBreakEndTimeMs.value = activeEndTime
                                        _lunchBreakRemainingSeconds.value = remainingSecs
                                        setDriverDutyState("Lunch Break")
                                        startLunchBreakCountdown(activeEndTime)
                                    }
                                } else {
                                    if (_lunchBreakUsedDate.value != today) {
                                        _isLunchBreakUsedToday.value = false
                                    }
                                }
                            }
                        }
                } catch (e: Exception) {
                    Log.w("CampusRideRepo", "Error attaching lunch break sync listener: ${e.message}")
                }
            }
        }
    }

    private fun startDailyResetTicker() {
        scope.launch(Dispatchers.Default) {
            while (true) {
                delay(15000L) // Check every 15 seconds for midnight rollover
                val today = CampusTimeUtils.getTodayCampusDate()
                val isUsedToday = (_lunchBreakUsedDate.value == today)
                if (_isLunchBreakUsedToday.value != isUsedToday) {
                    _isLunchBreakUsedToday.value = isUsedToday
                }
                val endTime = _lunchBreakEndTimeMs.value
                if (endTime > 0L && System.currentTimeMillis() >= endTime) {
                    clearActiveLunchBreakCountdownOnly()
                    if (_driverDutyState.value == "Lunch Break") {
                        setDriverDutyState("Available")
                    }
                }
            }
        }
    }

    private val _driverDutyState = MutableStateFlow("Available")
    val driverDutyState: StateFlow<String> = _driverDutyState.asStateFlow()

    private var heartbeatJob: Job? = null

    fun setDriverDutyState(status: String) {
        _driverDutyState.value = status
        startDriverHeartbeat()
        reevaluateEffectiveDriverAvailability()

        // Immediately push updated duty state to Firestore drivers collection
        val activeCartId = _selectedDriverCartId.value
        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()
                val isDutyAvailable = (status == "Available")
                val isInside = _isInsideGeofence.value
                val hasGps = _hasGpsLocation.value
                val activeAcceptedReq = _requests.value.find { it.status == RideRequestStatus.ACCEPTED }
                val isBusy = activeAcceptedReq != null
                val effectiveAvailable = isDutyAvailable && (!hasGps || isInside) && !isBusy

                val displayStatus = when {
                    status == "Lunch Break" -> "Lunch Break"
                    status == "Off Duty" -> "Offline"
                    isBusy -> "On Trip"
                    effectiveAvailable -> "Available"
                    else -> status
                }

                val doc = mapOf(
                    "cartId" to activeCartId,
                    "cartName" to (if (activeCartId == "cart_1") "Cart 1" else "Cart 2"),
                    "isOnline" to (status != "Off Duty"),
                    "onDuty" to isDutyAvailable,
                    "isAvailable" to effectiveAvailable,
                    "sessionId" to _driverSessionId.value,
                    "driverStatus" to displayStatus,
                    "status" to (if (status == "Off Duty") GolfCartStatus.OFFLINE.name else GolfCartStatus.HALTED.name),
                    "last_seen" to System.currentTimeMillis(),
                    "lastUpdatedMillis" to System.currentTimeMillis(),
                    "latitude" to (_driverLatitude.value ?: GeofenceManager.LIBRARY_LAT),
                    "longitude" to (_driverLongitude.value ?: GeofenceManager.LIBRARY_LNG)
                )
                firestore.collection("drivers")
                    .document(activeCartId)
                    .set(doc, SetOptions.merge())
                Log.d("CAMPUS_RIDE_AVAILABILITY", "DRIVER_DUTY_SYNC: Updated duty state to '$status' (isAvailable=$effectiveAvailable, driverStatus=$displayStatus) on drivers/$activeCartId")
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "Error syncing driver duty status: ${e.message}")
            }
        }
    }

    fun startDriverHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val activeCartId = _selectedDriverCartId.value
                    val currentRoleVal = _currentRole.value
                    if (currentRoleVal == UserRole.DRIVER) {
                        ensureFirebaseAuth()
                        val firestore = FirebaseFirestore.getInstance()
                        val isDutyAvailable = (_driverDutyState.value == "Available")
                        val isInside = _isInsideGeofence.value
                        val hasGps = _hasGpsLocation.value
                        val activeAcceptedReq = _requests.value.find { it.status == RideRequestStatus.ACCEPTED }
                        val isBusy = activeAcceptedReq != null
                        val effectiveAvailable = isDutyAvailable && (!hasGps || isInside) && !isBusy

                        val displayStatus = when {
                            _driverDutyState.value == "Lunch Break" -> "Lunch Break"
                            _driverDutyState.value == "Off Duty" -> "Offline"
                            isBusy -> "On Trip"
                            effectiveAvailable -> "Available"
                            else -> _driverDutyState.value
                        }

                        val heartbeatDoc = mapOf(
                            "cartId" to activeCartId,
                            "cartName" to (if (activeCartId == "cart_1") "Cart 1" else "Cart 2"),
                            "isOnline" to (_driverDutyState.value != "Off Duty"),
                            "onDuty" to isDutyAvailable,
                            "insideCampus" to isInside,
                            "isBusy" to isBusy,
                            "isAvailable" to effectiveAvailable,
                            "sessionId" to _driverSessionId.value,
                            "driverStatus" to displayStatus,
                            "status" to (if (_driverDutyState.value == "Off Duty") GolfCartStatus.OFFLINE.name else GolfCartStatus.HALTED.name),
                            "last_seen" to System.currentTimeMillis(),
                            "lastUpdatedMillis" to System.currentTimeMillis(),
                            "latitude" to (_driverLatitude.value ?: GeofenceManager.LIBRARY_LAT),
                            "longitude" to (_driverLongitude.value ?: GeofenceManager.LIBRARY_LNG)
                        )
                        firestore.collection("drivers")
                            .document(activeCartId)
                            .set(heartbeatDoc, SetOptions.merge())
                        Log.d("CAMPUS_RIDE_AVAILABILITY", "HEARTBEAT_TICK: Heartbeat published for drivers/$activeCartId (isAvailable=$effectiveAvailable, driverStatus=$displayStatus)")
                    }
                } catch (e: Exception) {
                    Log.w("CampusRideRepo", "Heartbeat sync error: ${e.message}")
                }
                delay(12000L) // 12s heartbeat
            }
        }
    }

    fun updateDriverGpsLocation(
        lat: Double,
        lng: Double,
        speedKmH: Int = 0,
        bearing: Float = 0f,
        accuracy: Float = 0f,
        cartId: String? = null
    ) {
        // Telemetry Validation & Anti-Spoofing Bounds Filter
        if (lat < 25.0 || lat > 26.0 || lng < 86.8 || lng > 87.3) {
            Log.w("CampusRideRepo", "SECURITY: Driver GPS location update rejected - coordinates outside campus bounds ($lat, $lng)")
            return
        }
        val clampedSpeed = speedKmH.coerceIn(0, 45)
        val clampedBearing = (bearing % 360f + 360f) % 360f

        _driverLatitude.value = lat
        _driverLongitude.value = lng
        _hasGpsLocation.value = true
        _driverGpsAccuracyMeters.value = accuracy

        val distMeters = GeofenceManager.calculateDistanceFromLibraryMeters(lat, lng)
        _distanceToLibraryMeters.value = distMeters

        // Automatic Campus Geofence Entry Detection & GPS Accuracy Validation
        val isAccuracyAcceptable = GeofenceManager.isGpsAccuracyValid(accuracy)
        val isInside = GeofenceManager.isInsideCampusGeofence(lat, lng, accuracy)
        _isInsideGeofence.value = isInside
        _isInsideCampus.value = isInside

        var activeCartId = cartId ?: _selectedDriverCartId.value

        // Check if currently assigned to an active accepted ride
        val activeAcceptedReq = _requests.value.find { it.status == RideRequestStatus.ACCEPTED }
        val isAssignedToRide = activeAcceptedReq != null
        val isOnDuty = (_driverDutyState.value == "Available")
        val effectiveAvailable = isOnDuty && isInside && !isAssignedToRide

        // AUTOMATIC DRIVER PRESENCE & CART ASSIGNMENT LOGIC
        if (isInside) {
            // Determine auto-assignment: If Cart 1 is occupied (< 45s), auto-assign Cart 2; otherwise Cart 1.
            val now = System.currentTimeMillis()
            val cart1LastUpdated = _cart1State.value.lastUpdatedMillis ?: 0L
            val isCart1Occupied = _cart1State.value.isTripActive && (now - cart1LastUpdated < 45000)
            val autoAssignedCart = if (cartId != null) cartId else if (isCart1Occupied) "cart_2" else "cart_1"

            activeCartId = autoAssignedCart
            _selectedDriverCartId.value = autoAssignedCart
            _isDriverAvailable.value = effectiveAvailable

            Log.d("CampusRideRepo", "DRIVER GPS UPDATE: Driver inside campus -> Assigned $autoAssignedCart -> isAvailable=$effectiveAvailable (OnDuty=$isOnDuty, Busy=$isAssignedToRide)")
        } else {
            // Driver is outside campus geofence
            _isDriverAvailable.value = false
            Log.d("CampusRideRepo", "DRIVER GPS UPDATE: Driver outside campus -> isAvailable=false")
        }

        val evaluated = com.example.location.CampusLandmarkZone.evaluateRoutePosition(
            latitude = lat,
            longitude = lng,
            bearing = clampedBearing,
            speedKmH = clampedSpeed,
            accuracy = accuracy,
            cartId = activeCartId
        )

        val distToGate = GeofenceManager.calculateDistanceMeters(lat, lng, GeofenceManager.GATE_LAT, GeofenceManager.GATE_LNG).roundToInt()
        val cartStatus = if (!isInside) GolfCartStatus.OFFLINE else if (speedKmH > 0) GolfCartStatus.MOVING else GolfCartStatus.HALTED
        val driverStatusString = if (isAssignedToRide) "On Trip" else if (effectiveAvailable) "Available" else if (isOnDuty) "Unavailable" else _driverDutyState.value

        val targetFlow = if (activeCartId == "cart_1") _cart1State else _cart2State
        val existing = targetFlow.value

        val updatedCart = existing.copy(
            cartId = activeCartId,
            cartName = if (activeCartId == "cart_1") "Cart 1" else "Cart 2",
            latitude = lat,
            longitude = lng,
            speedKmH = speedKmH,
            bearing = bearing,
            accuracy = accuracy,
            status = cartStatus,
            isTripActive = isAssignedToRide,
            isAvailable = effectiveAvailable,
            driverStatus = driverStatusString,
            lastUpdatedMillis = System.currentTimeMillis(),
            distanceToGateMeters = distToGate,
            distanceToUserMeters = distToGate,
            direction = evaluated?.directionSummary,
            currentStop = evaluated?.currentStopName,
            nextStop = evaluated?.nextStopName
        )

        targetFlow.value = updatedCart
        _fleetCarts.value = listOf(_cart1State.value, _cart2State.value)
        if (_selectedDriverCartId.value == activeCartId) {
            _golfCartState.value = updatedCart
        }

        // Broadcast continuous Live Driver Cart sync to Firestore drivers collection
        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()
                val driverDoc = mapOf(
                    "cartId" to activeCartId,
                    "cartName" to (if (activeCartId == "cart_1") "Cart 1" else "Cart 2"),
                    "latitude" to lat,
                    "longitude" to lng,
                    "bearing" to bearing,
                    "speedKmH" to speedKmH,
                    "accuracy" to accuracy,
                    "status" to cartStatus.name,
                    "isTripActive" to isAssignedToRide,
                    "isAvailable" to effectiveAvailable,
                    "onDuty" to isOnDuty,
                    "isOnline" to true,
                    "isBusy" to isAssignedToRide,
                    "insideCampus" to isInside,
                    "sessionId" to _driverSessionId.value,
                    "driverStatus" to driverStatusString,
                    "direction" to (evaluated?.directionSummary ?: "In Transit"),
                    "currentStop" to (evaluated?.currentStopName ?: "In Transit"),
                    "nextStop" to (evaluated?.nextStopName ?: "Next Stop"),
                    "lastUpdatedMillis" to System.currentTimeMillis(),
                    "last_seen" to System.currentTimeMillis(),
                    "distanceToGateMeters" to distToGate
                )
                firestore.collection("drivers")
                    .document(activeCartId)
                    .set(driverDoc, SetOptions.merge())
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "Sync driver live GPS to Firestore drivers collection notice: ${e.message}")
            }
        }

        // Real-time Driver GPS stream to active ACCEPTED ride request if present
        if (activeAcceptedReq != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    ensureFirebaseAuth()
                    FirebaseFirestore.getInstance()
                        .collection("ride_requests")
                        .document(activeAcceptedReq.id)
                        .update(
                            mapOf(
                                "driverLat" to lat,
                                "driverLng" to lng,
                                "driverBearing" to bearing,
                                "driverSpeedMps" to ((speedKmH * 1000f) / 3600f),
                                "driverLocationUpdatedAt" to System.currentTimeMillis()
                            )
                        )
                } catch (e: Exception) {
                    Log.w("CampusRideRepo", "Sync driver live GPS to active ride notice: ${e.message}")
                }
            }
        }
    }

    fun onGpsDisabledOrPermissionMissing() {
        _hasGpsLocation.value = false
        _isInsideGeofence.value = false
        _distanceToLibraryMeters.value = null
        reevaluateEffectiveDriverAvailability()
    }

    fun reevaluateEffectiveDriverAvailability() {
        if (_currentRole.value != UserRole.DRIVER) {
            // For Students and Faculty, driver availability is derived directly from the real-time fleet state
            val anyFleetAvailable = _fleetCarts.value.any { 
                (it.isLive || (it.isAvailable && !it.driverStatus.equals("Offline", ignoreCase = true))) && 
                !it.driverStatus.equals("Lunch Break", ignoreCase = true) 
            }
            _isDriverAvailable.value = anyFleetAvailable
            Log.d("CAMPUS_RIDE_AVAILABILITY", "RIDER_ROLE (${_currentRole.value}): Evaluated fleet availability = $anyFleetAvailable")
            return
        }

        val manualOnDuty = (_driverDutyState.value == "Available")
        val activeAcceptedReq = _requests.value.find { it.status == RideRequestStatus.ACCEPTED }
        val isOccupied = activeAcceptedReq != null || _driverDutyState.value == "Occupied" || _driverDutyState.value == "On Trip"

        // Driver is available if: On Duty ("Available") AND inside campus (or GPS initializing) AND not occupied
        val effectiveAvailable = manualOnDuty && (!_hasGpsLocation.value || _isInsideGeofence.value) && !isOccupied

        _isDriverAvailable.value = effectiveAvailable
        val displayStatus = when {
            _driverDutyState.value == "Lunch Break" -> "Lunch Break"
            _driverDutyState.value == "Off Duty" -> "Offline"
            isOccupied -> "On Trip"
            effectiveAvailable -> "Available"
            else -> "Unavailable"
        }
        val activeCartId = _selectedDriverCartId.value
        _golfCartState.value = _golfCartState.value?.copy(
            isAvailable = effectiveAvailable,
            isTripActive = isOccupied,
            driverStatus = displayStatus,
            status = if (effectiveAvailable || isOccupied) GolfCartStatus.HALTED else GolfCartStatus.OFFLINE
        )
        _fleetCarts.value = _fleetCarts.value.map {
            if (it.cartId == activeCartId) {
                it.copy(
                    isAvailable = effectiveAvailable,
                    isTripActive = isOccupied,
                    driverStatus = displayStatus,
                    status = if (effectiveAvailable || isOccupied) GolfCartStatus.HALTED else GolfCartStatus.OFFLINE
                )
            } else it
        }

        val targetFlow = if (activeCartId == "cart_1") _cart1State else _cart2State
        targetFlow.value = targetFlow.value.copy(
            isAvailable = effectiveAvailable,
            isTripActive = isOccupied,
            driverStatus = displayStatus,
            status = if (effectiveAvailable || isOccupied) GolfCartStatus.HALTED else GolfCartStatus.OFFLINE
        )

        Log.d("CAMPUS_RIDE_AVAILABILITY", "DRIVER_ROLE: Evaluated availability=$effectiveAvailable (manualOnDuty=$manualOnDuty, isInside=${_isInsideGeofence.value}, hasGps=${_hasGpsLocation.value}, isOccupied=$isOccupied)")

        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()
                firestore.collection("drivers")
                    .document(activeCartId)
                    .set(
                        mapOf(
                            "cartId" to activeCartId,
                            "cartName" to (if (activeCartId == "cart_1") "Cart 1" else "Cart 2"),
                            "isAvailable" to effectiveAvailable,
                            "isTripActive" to isOccupied,
                            "onDuty" to manualOnDuty,
                            "isOnline" to true,
                            "isBusy" to isOccupied,
                            "insideCampus" to _isInsideGeofence.value,
                            "driverStatus" to displayStatus,
                            "latitude" to (_driverLatitude.value ?: GeofenceManager.LIBRARY_LAT),
                            "longitude" to (_driverLongitude.value ?: GeofenceManager.LIBRARY_LNG),
                            "distanceToLibraryMeters" to (_distanceToLibraryMeters.value ?: 0.0),
                            "last_seen" to System.currentTimeMillis(),
                            "lastUpdatedMillis" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    )
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "Sync driver status update notice: ${e.message}")
            }
        }
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
        startGolfCartLiveTrackingListener()
        if (role == UserRole.DRIVER) {
            startDriverFirestoreListener()
            startDriverHeartbeat()
        } else {
            driverListenerRegistration?.remove()
            heartbeatJob?.cancel()
            heartbeatJob = null
        }
        reevaluateEffectiveDriverAvailability()
    }

    fun clearRole() {
        prefs.edit().remove("saved_user_role").apply()
        _currentRole.value = null
        driverListenerRegistration?.remove()
        studentListenerRegistration?.remove()
        cart1ListenerRegistration?.remove()
        cart2ListenerRegistration?.remove()
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun setDriverAvailable(available: Boolean) {
        _isDriverAvailable.value = available
        val activeCartId = _selectedDriverCartId.value
        val targetFlow = if (activeCartId == "cart_1") _cart1State else _cart2State
        targetFlow.value = targetFlow.value.copy(
            status = if (!available) GolfCartStatus.OFFLINE else GolfCartStatus.HALTED,
            isAvailable = available,
            driverStatus = if (available) "Available" else "Offline"
        )
        _golfCartState.value = targetFlow.value
    }

    fun startDriverTrip(context: Context, cartId: String) {
        setSelectedDriverCartId(cartId)
        _isTripActive.value = true
        prefs.edit().putBoolean("pref_is_trip_active", true).apply()
        com.example.location.DriverLocationService.startTrip(context, cartId)

        val targetFlow = if (cartId == "cart_1") _cart1State else _cart2State
        val updated = targetFlow.value.copy(
            cartId = cartId,
            cartName = if (cartId == "cart_1") "Cart 1" else "Cart 2",
            status = GolfCartStatus.HALTED,
            isTripActive = true,
            isAvailable = true,
            driverStatus = "On Duty",
            lastUpdatedMillis = System.currentTimeMillis()
        )
        targetFlow.value = updated
        _fleetCarts.value = listOf(_cart1State.value, _cart2State.value)
        _golfCartState.value = updated

        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                FirebaseFirestore.getInstance().collection("drivers")
                    .document(cartId)
                    .set(
                        mapOf(
                            "cartId" to cartId,
                            "cartName" to (if (cartId == "cart_1") "Cart 1" else "Cart 2"),
                            "status" to GolfCartStatus.HALTED.name,
                            "isTripActive" to true,
                            "isAvailable" to true,
                            "driverStatus" to "On Duty",
                            "lastUpdatedMillis" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    )
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "startDriverTrip sync error: ${e.message}")
            }
        }
    }

    fun endDriverTrip(context: Context, cartId: String) {
        _isTripActive.value = false
        prefs.edit().putBoolean("pref_is_trip_active", false).apply()
        com.example.location.DriverLocationService.stopTrip(context)

        val targetFlow = if (cartId == "cart_1") _cart1State else _cart2State
        val updated = targetFlow.value.copy(
            status = GolfCartStatus.OFFLINE,
            isTripActive = false,
            isAvailable = false,
            driverStatus = "Offline",
            speedKmH = 0,
            lastUpdatedMillis = System.currentTimeMillis()
        )
        targetFlow.value = updated
        _fleetCarts.value = listOf(_cart1State.value, _cart2State.value)
        _golfCartState.value = updated

        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                FirebaseFirestore.getInstance().collection("drivers")
                    .document(cartId)
                    .set(
                        mapOf(
                            "status" to GolfCartStatus.OFFLINE.name,
                            "isTripActive" to false,
                            "isAvailable" to false,
                            "driverStatus" to "Offline",
                            "speedKmH" to 0,
                            "lastUpdatedMillis" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    )
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "endDriverTrip sync error: ${e.message}")
            }
        }
    }

    fun updateDriverLiveLocation(
        cartId: String = _selectedDriverCartId.value,
        latitude: Double,
        longitude: Double,
        speedKmH: Int = 0,
        bearing: Float = 0f,
        accuracy: Float = 0f,
        targetLat: Double = GeofenceManager.GATE_LAT,
        targetLng: Double = GeofenceManager.GATE_LNG
    ) {
        updateDriverGpsLocation(
            lat = latitude,
            lng = longitude,
            speedKmH = speedKmH,
            bearing = bearing,
            accuracy = accuracy,
            cartId = cartId
        )
    }

    fun startGolfCartLiveTrackingListener() {
        cart1ListenerRegistration?.remove()
        cart2ListenerRegistration?.remove()

        scope.launch(Dispatchers.IO) {
            val authRes = ensureFirebaseAuth()
            if (authRes.isFailure) {
                Log.w("CampusRideRepo", "startGolfCartLiveTrackingListener: auth failure", authRes.exceptionOrNull())
                return@launch
            }
            withContext(Dispatchers.Main) {
                cart1ListenerRegistration?.remove()
                cart2ListenerRegistration?.remove()
                try {
                    val firestore = FirebaseFirestore.getInstance()
                    Log.d("CampusRideRepo", "Attaching realtime listeners on 'drivers/cart_1' and 'drivers/cart_2'...")

                    fun handleCartSnapshot(cartId: String, snapshot: com.google.firebase.firestore.DocumentSnapshot?) {
                        if (snapshot == null || !snapshot.exists()) return

                        val lat = snapshot.getDouble("latitude")
                        val lng = snapshot.getDouble("longitude")
                        val bearing = snapshot.getDouble("bearing")?.toFloat() ?: 0f
                        val speedKmH = snapshot.getLong("speedKmH")?.toInt() ?: 0
                        val statusStr = snapshot.getString("status") ?: "HALTED"
                        val status = try { GolfCartStatus.valueOf(statusStr) } catch (e: Exception) { GolfCartStatus.HALTED }
                        val isAvailable = snapshot.getBoolean("isAvailable") ?: true
                        val isTripActive = snapshot.getBoolean("isTripActive") ?: false
                        val driverStatus = snapshot.getString("driverStatus") ?: "Available"
                        val lastUpdated = snapshot.getLong("lastUpdatedMillis") ?: snapshot.getLong("last_seen") ?: System.currentTimeMillis()
                        val direction = snapshot.getString("direction")
                        val currentStop = snapshot.getString("currentStop")
                        val nextStop = snapshot.getString("nextStop")

                        // If driver on this phone is driving this cart, don't overwrite local live GPS
                        if (_currentRole.value == UserRole.DRIVER && _selectedDriverCartId.value == cartId && _hasGpsLocation.value) {
                            return
                        }

                        val existingCart = if (cartId == "cart_1") _cart1State.value else _cart2State.value
                        val effectiveLat = lat ?: existingCart.latitude
                        val effectiveLng = lng ?: existingCart.longitude

                        val currentDistGate = if (effectiveLat != null && effectiveLng != null) {
                            GeofenceManager.calculateDistanceMeters(effectiveLat, effectiveLng, GeofenceManager.GATE_LAT, GeofenceManager.GATE_LNG).roundToInt()
                        } else 0

                        val updatedCart = existingCart.copy(
                            cartId = cartId,
                            cartName = if (cartId == "cart_1") "Cart 1" else "Cart 2",
                            latitude = effectiveLat,
                            longitude = effectiveLng,
                            speedKmH = speedKmH,
                            bearing = bearing,
                            status = status,
                            isTripActive = isTripActive,
                            isAvailable = isAvailable,
                            driverStatus = driverStatus,
                            lastUpdatedMillis = lastUpdated,
                            distanceToGateMeters = currentDistGate,
                            distanceToUserMeters = currentDistGate,
                            direction = direction ?: existingCart.direction,
                            currentStop = currentStop ?: existingCart.currentStop,
                            nextStop = nextStop ?: existingCart.nextStop
                        )

                        if (cartId == "cart_1") {
                            _cart1State.value = updatedCart
                        } else {
                            _cart2State.value = updatedCart
                        }

                        _fleetCarts.value = listOf(_cart1State.value, _cart2State.value)
                        if (_selectedDriverCartId.value == cartId || _golfCartState.value?.cartId == cartId) {
                            _golfCartState.value = updatedCart
                        }

                        if (_currentRole.value != UserRole.DRIVER) {
                            val anyAvailable = _fleetCarts.value.any { 
                                (it.isLive || (it.isAvailable && !it.driverStatus.equals("Offline", ignoreCase = true))) && 
                                !it.driverStatus.equals("Lunch Break", ignoreCase = true) 
                            }
                            _isDriverAvailable.value = anyAvailable
                            Log.d("CAMPUS_RIDE_AVAILABILITY", "CART_SNAPSHOT_RECEIVED ($cartId): isLive=${updatedCart.isLive}, isAvailable=$isAvailable, driverStatus=$driverStatus -> Fleet available=$anyAvailable")
                        }
                    }

                    cart1ListenerRegistration = firestore.collection("drivers")
                        .document("cart_1")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.e("CampusRideRepo", "Error listening to drivers/cart_1", error)
                                return@addSnapshotListener
                            }
                            handleCartSnapshot("cart_1", snapshot)
                        }

                    cart2ListenerRegistration = firestore.collection("drivers")
                        .document("cart_2")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.e("CampusRideRepo", "Error listening to drivers/cart_2", error)
                                return@addSnapshotListener
                            }
                            handleCartSnapshot("cart_2", snapshot)
                        }
                } catch (e: Exception) {
                    Log.e("CampusRideRepo", "Error attaching drivers snapshot listeners", e)
                }
            }
        }
    }

    fun toggleOverrideHours(enabled: Boolean) {
        _overrideWorkingHours.value = enabled
        prefs.edit().putBoolean("pref_override_hours", enabled).apply()
    }

    suspend fun sendStudentRideRequest(
        studentLat: Double = 25.2531616,
        studentLng: Double = 87.0370730,
        studentName: String = "",
        studentsWaiting: Int = 1,
        pickupLocation: PickupLocation = PickupLocation.GATE,
        assignedCartId: String? = null
    ): Result<RideRequest> = withContext(Dispatchers.IO) {
        if (!requestCreationMutex.tryLock()) {
            return@withContext Result.failure(IllegalStateException("A ride request creation is currently in progress. Please wait."))
        }
        try {
            // Strict Role-Based Location Enforcement: Students can ONLY request from GATE
            val effectiveLocation = PickupLocation.GATE
            if (pickupLocation != PickupLocation.GATE) {
                return@withContext Result.failure(
                    IllegalStateException("Access Denied: Students can only request rides from the Gate.")
                )
            }

            // Anti-spam duplicate request protection
            val currentActive = _activeStudentRequest.value
            if (currentActive != null && (currentActive.status == RideRequestStatus.PENDING || currentActive.status == RideRequestStatus.ACCEPTED)) {
                return@withContext Result.failure(IllegalStateException("Anti-spam protection: You already have an active ride request in progress."))
            }

            val validatedWaitingCount = studentsWaiting.coerceIn(1, 10)

            val calculatedDistance = GeofenceManager.calculateDistanceMeters(
                studentLat,
                studentLng,
                effectiveLocation.latitude ?: GeofenceManager.GATE_LAT,
                effectiveLocation.longitude ?: GeofenceManager.GATE_LNG
            )

            // =========================================================================================
            // TODO: REMOVE BEFORE PRODUCTION RELEASE:
            // Temporary any-location ride request testing bypass.
            // When TEST_MODE_ALLOW_ANY_PICKUP_LOCATION = true, allows requests from any location.
            // When TEST_MODE_ALLOW_ANY_PICKUP_LOCATION = false, enforces the exact 70m Main Gate geofence.
            // =========================================================================================
            if (com.example.location.TEST_MODE_ALLOW_ANY_PICKUP_LOCATION) {
                Log.d("CampusRideRepo", "TEMPORARY TEST MODE: 70m Main Gate geofence bypassed for testing (${calculatedDistance.roundToInt()}m from gate).")
            } else {
                // EXISTING 70-meter Main Gate geofence rule PRESERVED INTACT
                if (calculatedDistance > GeofenceManager.MAX_GEOFENCE_METERS) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "Ride request rejected by backend: You are outside the 70-meter radius of IIIT Bhagalpur Gate (${calculatedDistance.roundToInt()}m from gate)."
                        )
                    )
                }
            }

            val schedule = ScheduleStatus.getCurrentStatus(_overrideWorkingHours.value)
            if (!schedule.isAvailable) {
                return@withContext Result.failure(IllegalStateException(schedule.message))
            }

            val allOnLunch = _fleetCarts.value.isNotEmpty() && _fleetCarts.value.all { it.driverStatus == "Lunch Break" }
            if (allOnLunch) {
                return@withContext Result.failure(IllegalStateException("All golf carts are on lunch break."))
            }

            if (_cooldownSeconds.value > 0) {
                return@withContext Result.failure(IllegalStateException("Please wait for cooldown timer before requesting again."))
            }

            val assignedCart = (if (assignedCartId != null) _fleetCarts.value.find { it.cartId == assignedCartId && (it.isLive || (it.isAvailable && !it.driverStatus.equals("Offline", ignoreCase = true))) && !it.driverStatus.equals("Lunch Break", ignoreCase = true) && !it.driverStatus.equals("Occupied", ignoreCase = true) } else null)
                ?: findBestAvailableCart(effectiveLocation.displayName)
                ?: return@withContext Result.failure(IllegalStateException("All golf carts are temporarily unavailable."))

            val request = RideRequest(
                requesterType = com.example.data.model.RequesterType.STUDENT,
                studentName = if (studentName.isNotBlank()) studentName else "Student Passenger",
                pickupLocation = effectiveLocation.id,
                distanceToGateMeters = calculatedDistance.roundToInt(),
                studentsWaiting = validatedWaitingCount,
                status = RideRequestStatus.PENDING,
                assignedCartId = assignedCart.cartId,
                assignedCartName = assignedCart.cartName
            )

            Log.d("RIDE_REQUEST_DISPATCH", "STUDENT_REQUEST_DISPATCH: requestId=${request.id}, studentsWaiting=$validatedWaitingCount, assignedCart=${assignedCart.cartId} (${assignedCart.cartName})")

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
                    "studentsWaiting" to request.studentsWaiting,
                    "status" to request.status.name,
                    "assignedCartId" to request.assignedCartId,
                    "assignedCartName" to request.assignedCartName,
                    "timestamp" to request.timestamp
                )

                firestore.collection("ride_requests")
                    .document(request.id)
                    .set(docData)
                    .awaitTask()

                Log.d("CAMPUS_RIDE_TRACE", "REQUEST_FIRESTORE_CREATED: requestId=${request.id}")

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
                    "passengerName" to request.studentName,
                    "distanceToGateMeters" to request.distanceToGateMeters,
                    "studentsWaiting" to request.studentsWaiting,
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

                Log.d("CAMPUS_RIDE_TRACE", "FCM_DISPATCH_CREATED: dispatchId=dispatch_${request.id}")

                _golfCartState.value = assignedCart
                _activeStudentRequest.value = request
                val updated = listOf(request) + _requests.value.filter { it.id != request.id }
                _requests.value = updated

                startStudentRequestListener(request.id)
                FcmRoleNotificationManager.dispatchDriverPush(request)

                // Asynchronously sync to Render backend with identical requestId to prevent duplicate creation
                scope.launch(Dispatchers.IO) {
                    try {
                        Log.d("NETWORK_TRACE", "Calling createRideRequest() with requestId=${request.id}")
                        CampusBackendClient.api.createRideRequest(
                            CreateRideRequest(
                                id = request.id,
                                requestId = request.id,
                                requesterType = request.requesterType.name,
                                studentName = request.studentName,
                                pickupLocation = request.pickupLocation,
                                distanceToGateMeters = request.distanceToGateMeters,
                                studentsWaiting = request.studentsWaiting,
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
        } finally {
            requestCreationMutex.unlock()
        }
    }

    suspend fun sendFacultyRideRequest(pickupLocation: String): Result<RideRequest> = withContext(Dispatchers.IO) {
        if (!requestCreationMutex.tryLock()) {
            return@withContext Result.failure(IllegalStateException("A ride request creation is currently in progress. Please wait."))
        }
        try {
            val facultyLoc = PickupLocation.fromId(pickupLocation)
            if (!facultyLoc.allowedForFaculty) {
                return@withContext Result.failure(
                    IllegalStateException("Access Denied: Invalid pickup location for Faculty ($pickupLocation). Allowed locations are Boys Hostel, Computer Centre, Trunkut, and Main Gate.")
                )
            }

            val currentActive = _activeFacultyRequest.value
            if (currentActive != null && (currentActive.status == RideRequestStatus.PENDING || currentActive.status == RideRequestStatus.ACCEPTED)) {
                return@withContext Result.failure(IllegalStateException("Anti-spam protection: You already have an active faculty ride request in progress."))
            }

            val schedule = ScheduleStatus.getCurrentStatus(_overrideWorkingHours.value)
            if (!schedule.isAvailable) {
                return@withContext Result.failure(IllegalStateException(schedule.message))
            }

            val allOnLunch = _fleetCarts.value.isNotEmpty() && _fleetCarts.value.all { it.driverStatus == "Lunch Break" }
            if (allOnLunch) {
                return@withContext Result.failure(IllegalStateException("All golf carts are on lunch break."))
            }

            val assignedCart = findBestAvailableCart(facultyLoc.displayName)
                ?: return@withContext Result.failure(IllegalStateException("All golf carts are temporarily unavailable."))

            val request = RideRequest(
                requesterType = com.example.data.model.RequesterType.FACULTY,
                studentName = "Faculty Member",
                pickupLocation = facultyLoc.id,
                distanceToGateMeters = 0,
                status = RideRequestStatus.PENDING,
                assignedCartId = assignedCart.cartId,
                assignedCartName = assignedCart.cartName
            )

            Log.d("RIDE_REQUEST_DISPATCH", "FACULTY_REQUEST_DISPATCH: requestId=${request.id}, pickup=${facultyLoc.displayName}, assignedCart=${assignedCart.cartId}")

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
                    "distanceToGateMeters" to 0,
                    "status" to request.status.name,
                    "assignedCartId" to request.assignedCartId,
                    "assignedCartName" to request.assignedCartName,
                    "timestamp" to request.timestamp
                )

                firestore.collection("ride_requests")
                    .document(request.id)
                    .set(docData)
                    .awaitTask()

                Log.d("CAMPUS_RIDE_TRACE", "REQUEST_FIRESTORE_CREATED: requestId=${request.id}")

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
                    "studentName" to "Faculty Member",
                    "passengerName" to "Faculty Member",
                    "distanceToGateMeters" to 0,
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

                Log.d("CAMPUS_RIDE_TRACE", "FCM_DISPATCH_CREATED: dispatchId=dispatch_${request.id}")

                _golfCartState.value = assignedCart
                _activeFacultyRequest.value = request
                val updated = listOf(request) + _requests.value.filter { it.id != request.id }
                _requests.value = updated

                startStudentRequestListener(request.id)
                FcmRoleNotificationManager.dispatchDriverPush(request)

                scope.launch(Dispatchers.IO) {
                    try {
                        Log.d("NETWORK_TRACE", "Calling createRideRequest() with requestId=${request.id}")
                        CampusBackendClient.api.createRideRequest(
                            CreateRideRequest(
                                id = request.id,
                                requestId = request.id,
                                requesterType = request.requesterType.name,
                                studentName = request.studentName,
                                pickupLocation = request.pickupLocation,
                                distanceToGateMeters = request.distanceToGateMeters ?: 0,
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
        } finally {
            requestCreationMutex.unlock()
        }
    }

    fun acceptRideRequest(requestId: String) {
        CriticalAlertManager.markRequestHandled(requestId)
        CriticalAlertManager.stopAlert(context, reason = "ACCEPTED")
        Log.d("CAMPUS_RIDE_TRACE", "DRIVER_ACCEPT: requestId=$requestId")

        val targetReq = _requests.value.find { it.id == requestId }
        val effectiveCartId = targetReq?.assignedCartId ?: _selectedDriverCartId.value

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

        Log.d("CAMPUS_RIDE_TRACE", "REQUEST_STATUS = ACCEPTED: requestId=$requestId")

        scope.launch(Dispatchers.IO) {
            try {
                ensureFirebaseAuth()
                val firestore = FirebaseFirestore.getInstance()
                val rideRef = firestore.collection("ride_requests").document(requestId)

                // Atomic Transaction: Verify status is PENDING before transitioning to ACCEPTED
                firestore.runTransaction { tx ->
                    val snap = tx.get(rideRef)
                    val currentStatus = snap.getString("status")
                    if (currentStatus != null && currentStatus != RideRequestStatus.PENDING.name && currentStatus != RideRequestStatus.ACCEPTED.name) {
                        throw IllegalStateException("Ride request is no longer pending ($currentStatus)")
                    }
                    tx.update(
                        rideRef,
                        mapOf(
                            "status" to RideRequestStatus.ACCEPTED.name,
                            "assignedCartId" to effectiveCartId,
                            "driverAcceptedAt" to System.currentTimeMillis(),
                            "updatedAt" to System.currentTimeMillis()
                        )
                    )
                }.awaitTask()

                firestore.collection("drivers")
                    .document(effectiveCartId)
                    .update(mapOf("isAvailable" to false, "driverStatus" to "Occupied"))
            } catch (e: Exception) {
                Log.e("CampusRideRepo", "Failed to update ACCEPTED status in Firestore transaction", e)
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
        CriticalAlertManager.markRequestHandled(requestId)
        CriticalAlertManager.stopAlert(context, reason = "REJECTED")
        Log.d("CAMPUS_RIDE_TRACE", "DRIVER_DECLINE: requestId=$requestId")

        val targetReq = _requests.value.find { it.id == requestId }
        val effectiveCartId = targetReq?.assignedCartId ?: _selectedDriverCartId.value
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
                    .document(effectiveCartId)
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
        val effectiveCartId = targetReq?.assignedCartId ?: _selectedDriverCartId.value
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
                    .document(effectiveCartId)
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

    private val refreshMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun refreshAllData(role: UserRole? = _currentRole.value): Result<Unit> = withContext(Dispatchers.IO) {
        if (!refreshMutex.tryLock()) {
            return@withContext Result.success(Unit)
        }
        try {
            var anyFetchSucceeded = false
            var networkError: Throwable? = null

            // 1. Fetch carts and active requests from Firestore
            try {
                val authRes = ensureFirebaseAuth()
                if (authRes.isSuccess) {
                    val firestore = FirebaseFirestore.getInstance()

                    // Ensure real-time listeners are active if not already
                    startGolfCartLiveTrackingListener()
                    if (role == UserRole.DRIVER) {
                        startDriverFirestoreListener()
                    }

                    // Explicitly fetch cart_1 snapshot
                    try {
                        val cart1Doc = firestore.collection("drivers").document("cart_1").get().awaitTask()
                        if (cart1Doc.exists()) {
                            val lat = cart1Doc.getDouble("latitude")
                            val lng = cart1Doc.getDouble("longitude")
                            val bearing = cart1Doc.getDouble("bearing")?.toFloat() ?: 0f
                            val speedKmH = cart1Doc.getLong("speedKmH")?.toInt() ?: 0
                            val statusStr = cart1Doc.getString("status") ?: "HALTED"
                            val status = try { GolfCartStatus.valueOf(statusStr) } catch (e: Exception) { GolfCartStatus.HALTED }
                            val isAvailable = cart1Doc.getBoolean("isAvailable") ?: true
                            val isTripActive = cart1Doc.getBoolean("isTripActive") ?: false
                            val driverStatus = cart1Doc.getString("driverStatus") ?: "Available"
                            val lastUpdated = cart1Doc.getLong("lastUpdatedMillis") ?: System.currentTimeMillis()
                            val direction = cart1Doc.getString("direction")
                            val currentStop = cart1Doc.getString("currentStop")
                            val nextStop = cart1Doc.getString("nextStop")

                            if (lat != null && lng != null) {
                                val currentDistGate = GeofenceManager.calculateDistanceMeters(lat, lng, GeofenceManager.GATE_LAT, GeofenceManager.GATE_LNG).roundToInt()
                                _cart1State.value = GolfCartState(
                                    cartId = "cart_1",
                                    cartName = "Cart 1",
                                    latitude = lat,
                                    longitude = lng,
                                    speedKmH = speedKmH,
                                    bearing = bearing,
                                    status = status,
                                    isTripActive = isTripActive,
                                    isAvailable = isAvailable,
                                    driverStatus = driverStatus,
                                    lastUpdatedMillis = lastUpdated,
                                    distanceToGateMeters = currentDistGate,
                                    distanceToUserMeters = currentDistGate,
                                    direction = direction,
                                    currentStop = currentStop,
                                    nextStop = nextStop
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("CampusRideRepo", "Refresh cart_1 fetch warning: ${e.message}")
                    }

                    // Explicitly fetch cart_2 snapshot
                    try {
                        val cart2Doc = firestore.collection("drivers").document("cart_2").get().awaitTask()
                        if (cart2Doc.exists()) {
                            val lat = cart2Doc.getDouble("latitude")
                            val lng = cart2Doc.getDouble("longitude")
                            val bearing = cart2Doc.getDouble("bearing")?.toFloat() ?: 0f
                            val speedKmH = cart2Doc.getLong("speedKmH")?.toInt() ?: 0
                            val statusStr = cart2Doc.getString("status") ?: "HALTED"
                            val status = try { GolfCartStatus.valueOf(statusStr) } catch (e: Exception) { GolfCartStatus.HALTED }
                            val isAvailable = cart2Doc.getBoolean("isAvailable") ?: true
                            val isTripActive = cart2Doc.getBoolean("isTripActive") ?: false
                            val driverStatus = cart2Doc.getString("driverStatus") ?: "Available"
                            val lastUpdated = cart2Doc.getLong("lastUpdatedMillis") ?: System.currentTimeMillis()
                            val direction = cart2Doc.getString("direction")
                            val currentStop = cart2Doc.getString("currentStop")
                            val nextStop = cart2Doc.getString("nextStop")

                            if (lat != null && lng != null) {
                                val currentDistGate = GeofenceManager.calculateDistanceMeters(lat, lng, GeofenceManager.GATE_LAT, GeofenceManager.GATE_LNG).roundToInt()
                                _cart2State.value = GolfCartState(
                                    cartId = "cart_2",
                                    cartName = "Cart 2",
                                    latitude = lat,
                                    longitude = lng,
                                    speedKmH = speedKmH,
                                    bearing = bearing,
                                    status = status,
                                    isTripActive = isTripActive,
                                    isAvailable = isAvailable,
                                    driverStatus = driverStatus,
                                    lastUpdatedMillis = lastUpdated,
                                    distanceToGateMeters = currentDistGate,
                                    distanceToUserMeters = currentDistGate,
                                    direction = direction,
                                    currentStop = currentStop,
                                    nextStop = nextStop
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("CampusRideRepo", "Refresh cart_2 fetch warning: ${e.message}")
                    }

                    _fleetCarts.value = listOf(_cart1State.value, _cart2State.value)
                    val selCart = if (_selectedDriverCartId.value == "cart_2") _cart2State.value else _cart1State.value
                    _golfCartState.value = selCart

                    // Fetch active ride requests
                    try {
                        val reqSnapshot = firestore.collection("ride_requests").get().awaitTask()
                        if (!reqSnapshot.isEmpty) {
                            val fetchedList = mutableListOf<RideRequest>()
                            for (doc in reqSnapshot.documents) {
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
                                    val studentsWaiting = doc.getLong("studentsWaiting")?.toInt()?.coerceIn(1, 10) ?: 1

                                    fetchedList.add(
                                        RideRequest(
                                            id = id,
                                            requesterType = reqType,
                                            studentName = studentName,
                                            pickupLocation = pickupLocation,
                                            distanceToGateMeters = distance,
                                            status = status,
                                            timestamp = timestamp,
                                            studentsWaiting = studentsWaiting,
                                            assignedCartId = cartId,
                                            assignedCartName = cartName
                                        )
                                    )
                                } catch (e: Exception) {
                                    Log.e("CampusRideRepo", "Error parsing request during refresh", e)
                                }
                            }
                            if (fetchedList.isNotEmpty()) {
                                _requests.value = fetchedList
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("CampusRideRepo", "Refresh ride_requests warning: ${e.message}")
                    }

                    // Check active user request status
                    val currentActiveReq = _activeStudentRequest.value
                    if (currentActiveReq != null) {
                        try {
                            val activeDoc = firestore.collection("ride_requests").document(currentActiveReq.id).get().awaitTask()
                            if (activeDoc.exists()) {
                                val statusStr = activeDoc.getString("status")
                                if (statusStr != null) {
                                    val status = try { RideRequestStatus.valueOf(statusStr) } catch (e: Exception) { currentActiveReq.status }
                                    val driverLat = activeDoc.getDouble("driverLat")
                                    val driverLng = activeDoc.getDouble("driverLng")
                                    val driverBearing = activeDoc.getDouble("driverBearing")?.toFloat() ?: 0f
                                    _activeStudentRequest.value = currentActiveReq.copy(
                                        status = status,
                                        driverLat = driverLat,
                                        driverLng = driverLng,
                                        driverBearing = driverBearing
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("CampusRideRepo", "Refresh activeStudentRequest warning: ${e.message}")
                        }
                    }

                    anyFetchSucceeded = true
                }
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "Firestore refresh exception: ${e.message}")
                networkError = e
            }

            // 2. Try REST backend API sync as well
            try {
                val backendCarts = CampusBackendClient.api.getCarts()
                if (backendCarts.isSuccessful && backendCarts.body()?.success == true) {
                    val carts = backendCarts.body()?.carts.orEmpty()
                    if (carts.isNotEmpty()) {
                        val updatedFleet = _fleetCarts.value.toMutableList()
                        for (c in carts) {
                            val idx = updatedFleet.indexOfFirst { it.cartId == c.cartId }
                            if (idx >= 0) {
                                val current = updatedFleet[idx]
                                updatedFleet[idx] = current.copy(
                                    latitude = c.latitude ?: current.latitude,
                                    longitude = c.longitude ?: current.longitude,
                                    speedKmH = c.speedKmH ?: current.speedKmH,
                                    driverStatus = c.driverStatus ?: current.driverStatus,
                                    isAvailable = c.isAvailable ?: current.isAvailable
                                )
                            }
                        }
                        _fleetCarts.value = updatedFleet
                    }
                    anyFetchSucceeded = true
                }
            } catch (e: Exception) {
                Log.w("CampusRideRepo", "REST backend refresh notice: ${e.message}")
                if (networkError == null) networkError = e
            }

            // 3. Re-evaluate local state
            checkAndRestoreLunchBreak()
            reevaluateEffectiveDriverAvailability()

            if (anyFetchSucceeded) {
                Result.success(Unit)
            } else if (networkError != null) {
                Result.failure(networkError)
            } else {
                Result.success(Unit)
            }
        } finally {
            refreshMutex.unlock()
        }
    }

    companion object {
        @Volatile
        var instance: CampusRideRepository? = null
    }
}
