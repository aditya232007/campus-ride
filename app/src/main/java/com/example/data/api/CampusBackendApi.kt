package com.example.data.api

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

const val RENDER_BACKEND_URL = "https://campus-ride-backend-df0n.onrender.com/"

@JsonClass(generateAdapter = true)
data class HealthResponse(
    val status: String?,
    val service: String?,
    val projectId: String?,
    val uptime: Long?
)

@JsonClass(generateAdapter = true)
data class LoginRequest(
    val role: String,
    val userId: String? = null,
    val email: String? = null,
    val accessCode: String? = null
)

@JsonClass(generateAdapter = true)
data class UserDto(
    val userId: String,
    val name: String,
    val email: String,
    val role: String,
    val department: String? = null,
    val phone: String? = null
)

@JsonClass(generateAdapter = true)
data class LoginResponse(
    val success: Boolean,
    val token: String? = null,
    val user: UserDto? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class CreateRideRequest(
    val id: String? = null,
    val requestId: String? = null,
    val requesterType: String,
    val studentName: String? = null,
    val pickupLocation: String,
    val dropoffLocation: String? = null,
    val distanceToGateMeters: Int? = null,
    val studentsWaiting: Int? = null,
    val assignedCartId: String? = null
)

@JsonClass(generateAdapter = true)
data class RideDto(
    val id: String,
    val requesterType: String,
    val studentName: String? = null,
    val pickupLocation: String,
    val dropoffLocation: String? = null,
    val distanceToGateMeters: Int? = null,
    val studentsWaiting: Int? = null,
    val status: String,
    val assignedCartId: String? = null,
    val assignedCartName: String? = null,
    val timestamp: Long? = null
)

@JsonClass(generateAdapter = true)
data class RideResponse(
    val success: Boolean,
    val message: String? = null,
    val ride: RideDto? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class RideListResponse(
    val success: Boolean,
    val count: Int? = 0,
    val rides: List<RideDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class CartDto(
    val cartId: String,
    val cartName: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedKmH: Int? = null,
    val bearing: Float? = null,
    val status: String? = null,
    val batteryLevel: Int? = null,
    val driverStatus: String? = null,
    val isAvailable: Boolean? = null,
    val etaMinutes: Int? = null
)

@JsonClass(generateAdapter = true)
data class CartListResponse(
    val success: Boolean,
    val count: Int? = 0,
    val carts: List<CartDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class LocationUpdateRequest(
    val cartId: String,
    val latitude: Double,
    val longitude: Double,
    val speedKmH: Int = 0,
    val bearing: Float = 0f
)

@JsonClass(generateAdapter = true)
data class DutyStatusRequest(
    val cartId: String,
    val driverStatus: String
)

@JsonClass(generateAdapter = true)
data class FcmTokenSyncRequest(
    val role: String,
    val userId: String? = null,
    val fcmToken: String
)

@JsonClass(generateAdapter = true)
data class BaseApiResponse(
    val success: Boolean,
    val message: String? = null,
    val error: String? = null
)

interface CampusBackendApi {
    @GET("health")
    suspend fun getHealth(): Response<HealthResponse>

    @POST("api/auth/login")
    suspend fun login(@Body req: LoginRequest): Response<LoginResponse>

    @POST("api/rides/request")
    suspend fun createRideRequest(@Body req: CreateRideRequest): Response<RideResponse>

    @GET("api/rides")
    suspend fun getRides(@Query("status") status: String? = null): Response<RideListResponse>

    @POST("api/rides/{id}/accept")
    suspend fun acceptRide(@Path("id") rideId: String): Response<RideResponse>

    @POST("api/rides/{id}/decline")
    suspend fun declineRide(@Path("id") rideId: String): Response<RideResponse>

    @POST("api/rides/{id}/complete")
    suspend fun completeRide(@Path("id") rideId: String): Response<RideResponse>

    @GET("api/carts")
    suspend fun getCarts(): Response<CartListResponse>

    @POST("api/carts/location")
    suspend fun updateCartLocation(@Body req: LocationUpdateRequest): Response<BaseApiResponse>

    @POST("api/carts/duty-status")
    suspend fun updateDutyStatus(@Body req: DutyStatusRequest): Response<BaseApiResponse>

    @POST("api/notifications/fcm-token")
    suspend fun syncFcmToken(@Body req: FcmTokenSyncRequest): Response<BaseApiResponse>
}

object CampusBackendClient {
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    val api: CampusBackendApi by lazy {
        Retrofit.Builder()
            .baseUrl(RENDER_BACKEND_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CampusBackendApi::class.java)
    }
}
