package com.aesprt.aquahub_customer.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub_customer.data.local.dao.SavedAddressDao
import com.aesprt.aquahub_customer.data.local.entity.toEntity
import com.aesprt.aquahub_customer.domain.AppResult
import com.aesprt.aquahub_customer.domain.AuthRepository
import com.aesprt.aquahub_customer.domain.CartLine
import com.aesprt.aquahub_customer.domain.CatalogRepository
import com.aesprt.aquahub_customer.domain.CreateOrderRequest
import com.aesprt.aquahub_customer.domain.CustomerOrder
import com.aesprt.aquahub_customer.domain.CustomerProfile
import com.aesprt.aquahub_customer.domain.DeliveryAddress
import com.aesprt.aquahub_customer.domain.DeliveryMode
import com.aesprt.aquahub_customer.domain.GeoPoint
import com.aesprt.aquahub_customer.domain.Money
import com.aesprt.aquahub_customer.domain.LocationRepository
import com.aesprt.aquahub_customer.domain.LocationSuggestion
import com.aesprt.aquahub_customer.domain.OrderRepository
import com.aesprt.aquahub_customer.domain.PaymentMethod
import com.aesprt.aquahub_customer.domain.PublicProduct
import com.aesprt.aquahub_customer.domain.PublicStation
import com.aesprt.aquahub_customer.domain.ResolvedLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime

const val MAX_NEAREST_STATION_DISTANCE_KM = 1.0

enum class StationFilter(val label: String) {
    ALL("All stations"),
    OPEN_NOW("Open now"),
    NEAREST("Nearest"),
}

data class CustomerUiState(
    val sessionReady: Boolean = false,
    val hasCompletedOnboarding: Boolean = false,
    val profile: CustomerProfile? = null,
    val firebaseConfigured: Boolean = false,
    val stations: List<PublicStation> = emptyList(),
    val stationsFromCache: Boolean = false,
    val stationsError: String? = null,
    val selectedStation: PublicStation? = null,
    val products: List<PublicProduct> = emptyList(),
    val productsFromCache: Boolean = false,
    val cart: List<CartLine> = emptyList(),
    val orders: List<CustomerOrder> = emptyList(),
    val location: GeoPoint? = null,
    val locationLabel: String = "Set your location",
    val locationLoading: Boolean = false,
    val searchQuery: String = "",
    val stationFilter: StationFilter = StationFilter.ALL,
    val deliveryMode: DeliveryMode = DeliveryMode.DELIVERY,
    val address: String = "",
    val addressPlaceId: String? = null,
    val savedAddresses: List<DeliveryAddress> = emptyList(),
    val selectedAddressIndex: Int = 0,
    val locationPickerQuery: String = "",
    val locationSuggestions: List<LocationSuggestion> = emptyList(),
    val locationPickerSelection: ResolvedLocation? = null,
    val locationPickerLoading: Boolean = false,
    val locationPickerError: String? = null,
    val customerNote: String = "",
    val businessRulesRead: Boolean = false,
    val businessRulesAccepted: Boolean = false,
    val submitting: Boolean = false,
    val message: String? = null,
    val maxNearestDistanceKm: Double = MAX_NEAREST_STATION_DISTANCE_KM,
) {
    val cartCount: Int get() = cart.sumOf { it.quantity }
    val subtotal: Money get() = cart.fold(Money.Zero) { total, item -> total + item.subtotal }
    val deliveryFee: Money get() = if (deliveryMode == DeliveryMode.DELIVERY && cart.isNotEmpty())
        selectedStation?.deliveryFee ?: Money.Zero else Money.Zero
    val total: Money get() = subtotal + deliveryFee

    val canPlaceOrder: Boolean
        get() = selectedStation?.businessRulesText?.isNotBlank() == true && businessRulesAccepted

    val distanceToSelectedStationKm: Double? get() = selectedStation?.distanceKmFrom(location)

    val isDeliveryInRange: Boolean get() {
        if (deliveryMode != DeliveryMode.DELIVERY) return true
        val station = selectedStation ?: return true
        val dist = distanceToSelectedStationKm ?: return true
        if (station.deliveryRadiusKm <= 0.0) return true
        return dist <= station.deliveryRadiusKm
    }

    val estimatedEtaMinutes: Int get() {
        val prepTime = selectedStation?.estimatedPreparationMinutes ?: 30
        if (deliveryMode == DeliveryMode.PICKUP) return prepTime
        val dist = distanceToSelectedStationKm ?: 0.0
        val travelTime = (dist * 5.0).toInt().coerceAtLeast(10)
        return prepTime + travelTime
    }

    val filteredStations: List<PublicStation> get() {
        val query = searchQuery.trim()
        return stations
            .filter { query.isBlank() || it.name.contains(query, true) || it.address.contains(query, true) }
    }

    fun displayedStations(now: LocalTime): List<PublicStation> = when (stationFilter) {
        StationFilter.ALL -> filteredStations.sortedWith(
            compareByDescending<PublicStation> { it.isAcceptingOrders }.thenBy { it.name.lowercase() },
        )
        StationFilter.OPEN_NOW -> filteredStations
            .filter { it.isOpenAt(now) }
            .sortedBy { it.distanceKmFrom(location) ?: Double.MAX_VALUE }
        StationFilter.NEAREST -> {
            val userLocation = location
            if (userLocation != null) {
                filteredStations
                    .filter { station ->
                        val dist = station.distanceKmFrom(userLocation)
                        dist != null && dist <= maxNearestDistanceKm
                    }
                    .sortedWith(
                        compareBy<PublicStation> { it.distanceKmFrom(userLocation) ?: Double.MAX_VALUE }
                            .thenBy { it.name.lowercase() }
                    )
            } else {
                emptyList()
            }
        }
    }
}

class CustomerViewModel(
    private val authRepository: AuthRepository,
    private val catalogRepository: CatalogRepository,
    private val locationRepository: LocationRepository,
    private val orderRepository: OrderRepository,
    private val savedAddressDao: SavedAddressDao? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(CustomerUiState(firebaseConfigured = authRepository.isFirebaseConfigured))
    val state: StateFlow<CustomerUiState> = _state.asStateFlow()
    private var productJob: Job? = null
    private var orderJob: Job? = null
    private var locationSearchJob: Job? = null

    init {
        if (savedAddressDao != null) {
            viewModelScope.launch {
                savedAddressDao.getAll().collect { entities ->
                    val domainList = entities.map { it.toDomain() }
                    _state.update { current ->
                        current.copy(savedAddresses = domainList).withSelectedSavedAddressIfBlank()
                    }
                }
            }
        }
        viewModelScope.launch {
            authRepository.profile.collect { profile ->
                _state.update { it.copy(profile = profile, sessionReady = true) }
                orderJob?.cancel()
                if (profile != null) {
                    orderJob = launch {
                        orderRepository.observeOrders().collect { orders -> _state.update { it.copy(orders = orders) } }
                    }
                } else {
                    _state.update { it.copy(orders = emptyList(), cart = emptyList()) }
                }
            }
        }
        viewModelScope.launch {
            catalogRepository.observeStations().collect { snapshot ->
                _state.update { current ->
                    val refreshedSelectedStation = current.selectedStation?.let { selected ->
                        snapshot.items.firstOrNull { it.id == selected.id } ?: selected
                    }
                    val rulesChanged = current.selectedStation?.businessRulesVersion !=
                        refreshedSelectedStation?.businessRulesVersion
                    current.copy(
                        stations = snapshot.items,
                        stationsFromCache = snapshot.isFromCache,
                        stationsError = snapshot.errorMessage,
                        selectedStation = refreshedSelectedStation,
                        businessRulesRead = if (rulesChanged) false else current.businessRulesRead,
                        businessRulesAccepted = if (rulesChanged) false else current.businessRulesAccepted,
                    )
                }
            }
        }
    }

    fun beginGoogleSignIn(onReady: (Intent) -> Unit) = viewModelScope.launch {
        when (val result = authRepository.freshGoogleSignInIntent()) {
            is AppResult.Success -> onReady(result.value)
            is AppResult.Failure -> showMessage(result.message)
        }
    }

    fun completeGoogleSignIn(data: Intent?) = viewModelScope.launch {
        when (val result = authRepository.completeGoogleSignIn(data)) {
            is AppResult.Success -> _state.update { it.copy(profile = result.value) }
            is AppResult.Failure -> showMessage(result.message)
        }
    }

    fun updateProfile(name: String, phone: String) = viewModelScope.launch {
        when (val result = authRepository.updateProfile(name, phone)) {
            is AppResult.Success -> _state.update { it.copy(profile = result.value, message = "Profile saved.") }
            is AppResult.Failure -> showMessage(result.message)
        }
    }

    fun signOut() = viewModelScope.launch { authRepository.signOut() }

    fun setSearch(query: String) = _state.update { it.copy(searchQuery = query) }

    fun setStationFilter(filter: StationFilter) = _state.update { it.copy(stationFilter = filter) }

    fun setLocation(point: GeoPoint?, label: String) = _state.update { it.copy(location = point, locationLabel = label) }

    fun refreshLocation() = viewModelScope.launch {
        _state.update { it.copy(locationLoading = true) }
        when (val result = locationRepository.currentLocation()) {
            is AppResult.Success -> when (val resolved = locationRepository.reverseGeocode(result.value)) {
                is AppResult.Success -> setDeliveryLocation(resolved.value, locationLoading = false)
                is AppResult.Failure -> _state.update {
                    it.copy(location = result.value, locationLabel = "Current location", locationLoading = false)
                }
            }
            is AppResult.Failure -> _state.update {
                it.copy(locationLoading = false, locationLabel = "Location unavailable", message = result.message)
            }
        }
    }

    fun beginLocationPicker(initialAddress: String?, initialLocation: GeoPoint?) {
        locationSearchJob?.cancel()
        _state.update {
            it.copy(
                locationPickerQuery = initialAddress.orEmpty(),
                locationSuggestions = emptyList(),
                locationPickerSelection = initialLocation?.let { point ->
                    ResolvedLocation(formattedAddress = initialAddress.orEmpty().ifBlank { "Selected location" }, point = point)
                },
                locationPickerLoading = false,
                locationPickerError = null,
            )
        }
    }

    fun searchLocations(query: String) {
        _state.update {
            it.copy(
                locationPickerQuery = query,
                locationPickerLoading = query.trim().length >= 2,
                locationPickerError = null,
                locationSuggestions = if (query.trim().length < 2) emptyList() else it.locationSuggestions,
            )
        }
        locationSearchJob?.cancel()
        if (query.trim().length < 2) return
        locationSearchJob = viewModelScope.launch {
            delay(300)
            when (val result = locationRepository.searchPlaces(query)) {
                is AppResult.Success -> _state.update {
                    it.copy(locationSuggestions = result.value, locationPickerLoading = false)
                }
                is AppResult.Failure -> _state.update {
                    it.copy(locationSuggestions = emptyList(), locationPickerLoading = false, locationPickerError = result.message)
                }
            }
        }
    }

    fun selectLocationSuggestion(suggestion: LocationSuggestion) = viewModelScope.launch {
        _state.update { it.copy(locationPickerLoading = true, locationPickerError = null) }
        when (val result = locationRepository.resolvePlace(suggestion.placeId)) {
            is AppResult.Success -> _state.update {
                it.copy(
                    locationPickerQuery = result.value.formattedAddress,
                    locationSuggestions = emptyList(),
                    locationPickerSelection = result.value,
                    locationPickerLoading = false,
                )
            }
            is AppResult.Failure -> _state.update {
                it.copy(locationPickerLoading = false, locationPickerError = result.message)
            }
        }
    }

    fun selectMapLocation(point: GeoPoint) = viewModelScope.launch {
        _state.update { it.copy(locationPickerLoading = true, locationPickerError = null) }
        when (val result = locationRepository.reverseGeocode(point)) {
            is AppResult.Success -> _state.update {
                it.copy(
                    locationPickerQuery = result.value.formattedAddress,
                    locationSuggestions = emptyList(),
                    locationPickerSelection = result.value,
                    locationPickerLoading = false,
                )
            }
            is AppResult.Failure -> _state.update {
                it.copy(locationPickerLoading = false, locationPickerError = result.message)
            }
        }
    }

    fun useCurrentLocationInPicker() = viewModelScope.launch {
        _state.update { it.copy(locationPickerLoading = true, locationPickerError = null) }
        when (val result = locationRepository.currentLocation()) {
            is AppResult.Success -> selectMapLocation(result.value)
            is AppResult.Failure -> _state.update {
                it.copy(locationPickerLoading = false, locationPickerError = result.message)
            }
        }
    }

    fun setDeliveryLocation(location: ResolvedLocation, locationLoading: Boolean = false) = _state.update {
        it.copy(
            location = location.point,
            locationLabel = location.formattedAddress,
            locationLoading = locationLoading,
            address = location.formattedAddress,
            addressPlaceId = location.placeId,
        )
    }

    fun selectStation(station: PublicStation) {
        if (_state.value.selectedStation?.id != station.id && _state.value.cart.isNotEmpty()) {
            _state.update { it.copy(cart = emptyList(), message = "Cart cleared because you changed stations.") }
        }
        _state.update {
            it.copy(
                selectedStation = station,
                products = emptyList(),
                businessRulesRead = false,
                businessRulesAccepted = false
            )
        }
        productJob?.cancel()
        productJob = viewModelScope.launch {
            catalogRepository.observeProducts(station.businessId, station.id).collect { snapshot ->
                _state.update { it.copy(products = snapshot.items, productsFromCache = snapshot.isFromCache) }
            }
        }
    }

    fun addProduct(product: PublicProduct) {
        if (!product.isAvailable) return
        _state.update { state ->
            val current = state.cart.firstOrNull { it.product.id == product.id }
            val updated = if (current == null) state.cart + CartLine(product, 1)
            else state.cart.map { if (it.product.id == product.id) it.copy(quantity = (it.quantity + 1).coerceAtMost(99)) else it }
            state.copy(cart = updated)
        }
    }

    fun changeQuantity(productId: String, delta: Int) = _state.update { state ->
        val updated = state.cart.mapNotNull {
            if (it.product.id != productId) it
            else (it.quantity + delta).let { quantity -> if (quantity <= 0) null else it.copy(quantity = quantity.coerceAtMost(99)) }
        }
        state.copy(cart = updated)
    }

    fun setDeliveryMode(mode: DeliveryMode) = _state.update {
        it.copy(deliveryMode = mode).withSelectedSavedAddressIfBlank()
    }
    fun setAddress(address: String) = _state.update { it.copy(address = address) }
    fun setCustomerNote(note: String) = _state.update { it.copy(customerNote = note.take(500)) }

    fun setBusinessRulesRead(read: Boolean) = _state.update {
        if (read) it.copy(businessRulesRead = true) else it
    }

    fun setBusinessRulesAccepted(accepted: Boolean) = _state.update {
        it.copy(businessRulesAccepted = accepted && it.businessRulesRead)
    }

    fun prepareCheckout() = _state.update { it.withSelectedSavedAddressIfBlank() }

    fun submitOrder(onCreated: (CustomerOrder) -> Unit) {
        val state = _state.value
        val station = state.selectedStation ?: return showMessage("Select a station.")
        if (!station.isAcceptingOrders) return showMessage("This station is not accepting orders right now.")
        if (!state.isDeliveryInRange) {
            val radius = station.deliveryRadiusKm
            return showMessage("The selected delivery address is outside this station's delivery area ($radius km).")
        }
        if (station.businessRulesText.isNullOrBlank()) {
            return showMessage("This station has not published its rules yet. Please choose another station or try again later.")
        }
        if (!state.businessRulesAccepted) {
            return showMessage("Read and accept this station's rules before placing the order.")
        }
        if (state.profile?.phone.isNullOrBlank()) return showMessage("Add your phone number in Account before ordering.")
        val request = CreateOrderRequest(
            businessId = station.businessId,
            stationId = station.id,
            items = state.cart,
            deliveryAddress = if (state.deliveryMode == DeliveryMode.DELIVERY) {
                DeliveryAddress("Delivery", state.address, state.location, state.addressPlaceId)
            } else null,
            deliveryMode = state.deliveryMode,
            paymentMethod = if (state.deliveryMode == DeliveryMode.DELIVERY) PaymentMethod.CASH_ON_DELIVERY else PaymentMethod.CASH,
            customerNote = state.customerNote.ifBlank { null },
            acceptedBusinessRulesVersion = station.businessRulesVersion,
        )
        request.validate().firstOrNull()?.let { return showMessage(it) }
        viewModelScope.launch {
            _state.update { it.copy(submitting = true) }
            when (val result = orderRepository.createOrder(request)) {
                is AppResult.Success -> {
                    _state.update {
                        it.copy(
                            submitting = false,
                            cart = emptyList(),
                            customerNote = "",
                            businessRulesRead = false,
                            businessRulesAccepted = false,
                            message = "Order placed successfully."
                        )
                    }
                    onCreated(result.value)
                }
                is AppResult.Failure -> _state.update { it.copy(submitting = false, message = result.message) }
            }
        }
    }

    fun finishOnboarding() = _state.update { it.copy(hasCompletedOnboarding = true) }

    fun addSavedAddress(address: DeliveryAddress) {
        if (savedAddressDao != null) {
            viewModelScope.launch {
                savedAddressDao.insert(address.toEntity())
            }
        } else {
            _state.update {
                it.copy(savedAddresses = it.savedAddresses + address)
                    .withSelectedSavedAddressIfBlank()
            }
        }
    }

    fun selectSavedAddress(index: Int) = _state.update { state ->
        val selected = state.savedAddresses.getOrNull(index)
        if (selected != null) {
            state.copy(
                selectedAddressIndex = index,
                address = selected.addressLine,
                addressPlaceId = selected.placeId,
                location = selected.location,
                locationLabel = "${selected.label} (${selected.addressLine.take(24)}...)"
            )
        } else state
    }

    fun deleteSavedAddress(index: Int) {
        val addressToDelete = _state.value.savedAddresses.getOrNull(index)
        if (savedAddressDao != null && addressToDelete != null) {
            viewModelScope.launch {
                savedAddressDao.deleteByAddressLine(addressToDelete.addressLine)
            }
        } else {
            _state.update { state ->
                val updated = state.savedAddresses.toMutableList().apply {
                    if (index in indices) removeAt(index)
                }
                state.copy(
                    savedAddresses = updated,
                    selectedAddressIndex = state.selectedAddressIndex.coerceAtMost(maxOf(0, updated.size - 1))
                )
            }
        }
    }

    fun reorder(order: CustomerOrder, onReady: () -> Unit) {
        val station = _state.value.stations.firstOrNull { it.id == order.stationId }
        if (station == null) {
            showMessage("Station is currently not available for ordering.")
            return
        }
        if (!station.isAcceptingOrders) {
            showMessage("This station is currently closed or not accepting orders.")
            return
        }

        selectStation(station)
        _state.update { state ->
            state.copy(
                cart = order.items.filter { it.product.isAvailable },
                deliveryMode = order.deliveryMode,
                address = if (order.deliveryAddress.isNotBlank()) order.deliveryAddress else state.address,
                addressPlaceId = null,
                location = order.deliveryLocation ?: state.location,
                customerNote = order.customerNote.orEmpty(),
                message = "Reorder items loaded into your cart."
            )
        }
        onReady()
    }

    fun cancelOrder(orderId: String, reason: String = "Cancelled by customer") = viewModelScope.launch {
        when (val result = orderRepository.cancelOrder(orderId, reason)) {
            is AppResult.Success -> showMessage("Cancellation requested.")
            is AppResult.Failure -> showMessage(result.message)
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
    private fun showMessage(message: String) = _state.update { it.copy(message = message) }
}

internal fun CustomerUiState.withSelectedSavedAddressIfBlank(): CustomerUiState {
    if (deliveryMode != DeliveryMode.DELIVERY || address.isNotBlank()) return this

    val addressIndex = selectedAddressIndex
        .takeIf { it in savedAddresses.indices }
        ?: savedAddresses.indices.firstOrNull()
        ?: return this
    val savedAddress = savedAddresses[addressIndex]
    if (savedAddress.addressLine.isBlank()) return this

    return copy(
        selectedAddressIndex = addressIndex,
        address = savedAddress.addressLine,
        addressPlaceId = savedAddress.placeId,
        location = savedAddress.location,
        locationLabel = "${savedAddress.label} (${savedAddress.addressLine.take(24)}...)",
    )
}
