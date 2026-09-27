package com.aesprt.aquahub_customer.domain

import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val profile: Flow<CustomerProfile?>
    val isFirebaseConfigured: Boolean
    suspend fun signInWithGoogle(): AppResult<CustomerProfile>

    suspend fun registerEmail(name: String, email: String, password: String): AppResult<CustomerProfile>
    suspend fun signInEmail(email: String, password: String): AppResult<CustomerProfile>
    suspend fun sendPasswordReset(email: String): AppResult<Unit>
    suspend fun resendEmailVerification(): AppResult<Unit>
    suspend fun refreshProfile(): AppResult<CustomerProfile>
    suspend fun reauthenticateEmail(email: String, password: String): AppResult<Unit>
    suspend fun reauthenticateGoogle(): AppResult<Unit>
    suspend fun updateProfile(name: String, phone: String, address: DeliveryAddress? = null): AppResult<CustomerProfile>
    suspend fun setPreferredStation(destination: PreferredStation): AppResult<CustomerProfile>
    suspend fun deleteAccount(): AppResult<Unit>
    suspend fun signOut()
}

data class PreferredStation(
    val businessId: String,
    val stationId: String,
    val acquisitionSource: CustomerAcquisitionSource,
)

interface LocationRepository {
    suspend fun currentLocation(): AppResult<GeoPoint>
    suspend fun searchPlaces(query: String): AppResult<List<LocationSuggestion>>
    suspend fun resolvePlace(placeId: String): AppResult<ResolvedLocation>
    suspend fun reverseGeocode(point: GeoPoint): AppResult<ResolvedLocation>
}

interface CatalogRepository {
    fun observeStation(businessId: String, stationId: String): Flow<CatalogSnapshot<PublicStation>>
    fun observeProducts(businessId: String, stationId: String): Flow<CatalogSnapshot<PublicProduct>>
    suspend fun resolveStation(publicStationCode: String): AppResult<PublicStation>
}

interface OrderRepository {
    fun observeOrders(): Flow<OrderSnapshot>
    suspend fun createOrder(request: CreateOrderRequest): AppResult<CustomerOrder>
    suspend fun cancelOrder(orderId: String, reason: String): AppResult<Unit>
}

data class OrderSnapshot(
    val items: List<CustomerOrder> = emptyList(),
    val isFromCache: Boolean = false,
    val errorMessage: String? = null,
)
