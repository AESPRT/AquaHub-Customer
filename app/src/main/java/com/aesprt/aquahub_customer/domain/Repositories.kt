package com.aesprt.aquahub_customer.domain

import android.content.Intent
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val profile: Flow<CustomerProfile?>
    val isFirebaseConfigured: Boolean
    suspend fun freshGoogleSignInIntent(): AppResult<Intent>
    suspend fun completeGoogleSignIn(data: Intent?): AppResult<CustomerProfile>
    suspend fun updateProfile(name: String, phone: String): AppResult<CustomerProfile>
    suspend fun signOut()
}

interface LocationRepository {
    suspend fun currentLocation(): AppResult<GeoPoint>
    suspend fun searchPlaces(query: String): AppResult<List<LocationSuggestion>>
    suspend fun resolvePlace(placeId: String): AppResult<ResolvedLocation>
    suspend fun reverseGeocode(point: GeoPoint): AppResult<ResolvedLocation>
}

interface CatalogRepository {
    fun observeStations(): Flow<CatalogSnapshot<PublicStation>>
    fun observeProducts(businessId: String, stationId: String): Flow<CatalogSnapshot<PublicProduct>>
}

interface OrderRepository {
    fun observeOrders(): Flow<List<CustomerOrder>>
    suspend fun createOrder(request: CreateOrderRequest): AppResult<CustomerOrder>
    suspend fun cancelOrder(orderId: String, reason: String): AppResult<Unit>
}
