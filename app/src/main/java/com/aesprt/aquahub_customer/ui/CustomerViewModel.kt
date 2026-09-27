package com.aesprt.aquahub_customer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub_customer.data.local.dao.SavedAddressDao
import com.aesprt.aquahub_customer.data.local.entity.toEntity
import com.aesprt.aquahub_customer.data.preferences.ThemePreferences
import com.aesprt.aquahub_customer.domain.AppResult
import com.aesprt.aquahub_customer.domain.AuthRepository
import com.aesprt.aquahub_customer.domain.CartLine
import com.aesprt.aquahub_customer.domain.CatalogRepository
import com.aesprt.aquahub_customer.domain.CreateOrderRequest
import com.aesprt.aquahub_customer.domain.CustomerOrder
import com.aesprt.aquahub_customer.domain.CustomerProfile
import com.aesprt.aquahub_customer.domain.CustomerAcquisitionSource
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
import com.aesprt.aquahub_customer.domain.PendingStationLink
import com.aesprt.aquahub_customer.domain.PreferredStation
import com.aesprt.aquahub_customer.domain.ResolvedLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime

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
    val pendingStationSelection: PublicStation? = null,
    val browsingDiscovery: Boolean = false,
    val pendingStationLink: PendingStationLink? = null,
    val resolvingStationLink: Boolean = false,
    val products: List<PublicProduct> = emptyList(),
    val productsFromCache: Boolean = false,
    val cart: List<CartLine> = emptyList(),
    val orders: List<CustomerOrder> = emptyList(),
    val ordersFromCache: Boolean = false,
    val ordersError: String? = null,
    val location: GeoPoint? = null,
    val locationLabel: String = "Set your location",
    val locationLoading: Boolean = false,
    val searchQuery: String = "",
    val stationFilter: StationFilter = StationFilter.ALL,
    val deliveryMode: DeliveryMode = DeliveryMode.DELIVERY,
    val selectedPaymentMethod: PaymentMethod? = PaymentMethod.CASH_ON_DELIVERY,
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
    val authLoading: Boolean = false,
    val authError: String? = null,
    val deletingAccount: Boolean = false,
) {
    val cartCount: Int get() = cart.sumOf { it.quantity }
    val subtotal: Money get() = cart.fold(Money.Zero) { total, item -> total + item.subtotal }
    val regularSubtotal: Money get() = cart.fold(Money.Zero) { total, item -> total + (item.product.price * item.quantity) }
    val promotionSavings: Money get() = Money((regularSubtotal.centavos - subtotal.centavos).coerceAtLeast(0L))
    val deliveryFee: Money get() = if (deliveryMode == DeliveryMode.DELIVERY && cart.isNotEmpty())
        selectedStation?.deliveryFee ?: Money.Zero else Money.Zero
    val total: Money get() = subtotal + deliveryFee

    val availablePaymentMethods: List<PaymentMethod> get() = buildList {
        val station = selectedStation ?: return@buildList
        if (deliveryMode == DeliveryMode.DELIVERY && station.codPaymentEnabled) add(PaymentMethod.CASH_ON_DELIVERY)
        if (deliveryMode == DeliveryMode.PICKUP && station.cashPaymentEnabled) add(PaymentMethod.CASH)
        if (station.gcashPaymentEnabled) add(PaymentMethod.GCASH)
        if (station.mayaPaymentEnabled) add(PaymentMethod.MAYA)
    }

    val canPlaceOrder: Boolean
        get() = selectedStation?.businessRulesText?.isNotBlank() == true &&
            businessRulesAccepted &&
            selectedPaymentMethod in availablePaymentMethods

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
        val preferredBusinessId = profile?.preferredBusinessId
        val preferredStationId = profile?.preferredStationId
        return stations
            .filter {
                !preferredBusinessId.isNullOrBlank() &&
                    !preferredStationId.isNullOrBlank() &&
                    it.businessId == preferredBusinessId &&
                    it.id == preferredStationId
            }
            .filter { query.isBlank() || it.name.contains(query, true) || it.address.contains(query, true) }
    }

    val preferredStation: PublicStation? get() = stations.singleOrNull()

    val hasLinkedStation: Boolean get() =
        !profile?.preferredBusinessId.isNullOrBlank() && !profile.preferredStationId.isNullOrBlank()

    val isStationOwnedCustomer: Boolean get() = hasLinkedStation

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
                    .mapNotNull { station ->
                        station.distanceKmFrom(userLocation)?.let { distance -> distance to station }
                    }
                    .minWithOrNull(
                        compareBy<Pair<Double, PublicStation>> { it.first }
                            .thenBy { it.second.name.lowercase() },
                    )
                    ?.second
                    ?.let(::listOf)
                    .orEmpty()
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
    private val preferences: ThemePreferences? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(CustomerUiState(firebaseConfigured = authRepository.isFirebaseConfigured))
    val state: StateFlow<CustomerUiState> = _state.asStateFlow()
    private var productJob: Job? = null
    private var orderJob: Job? = null
    private var stationJob: Job? = null
    private var locationSearchJob: Job? = null
    private var addressJob: Job? = null
    private var pendingLinkJob: Job? = null

    init {
        viewModelScope.launch {
            preferences?.pendingStationLink?.collect { persisted ->
                if (persisted != null && _state.value.pendingStationLink == null) {
                    _state.update { it.copy(pendingStationLink = persisted, browsingDiscovery = false) }
                    if (_state.value.profile != null) resolvePendingStationLink()
                }
            }
        }
        viewModelScope.launch {
            authRepository.profile.collect { profile ->
                val previousUid = _state.value.profile?.uid
                val accountChanged = previousUid != null && previousUid != profile?.uid
                _state.update {
                    it.copy(
                        profile = profile,
                        sessionReady = true,
                        cart = if (accountChanged) emptyList() else it.cart,
                        orders = if (accountChanged) emptyList() else it.orders,
                        savedAddresses = if (accountChanged) emptyList() else it.savedAddresses,
                        ordersFromCache = if (accountChanged) false else it.ordersFromCache,
                        ordersError = if (accountChanged) null else it.ordersError,
                        pendingStationSelection = null,
                    )
                }
                addressJob?.cancel()
                orderJob?.cancel()
                stationJob?.cancel()
                if (profile != null) {
                    addressJob = launch {
                        savedAddressDao?.getAll(profile.uid)?.collect { entities ->
                            _state.update { current -> current.copy(savedAddresses = entities.map { it.toDomain() }).withSelectedSavedAddressIfBlank() }
                        }
                    }
                    orderJob = observeOrders(profile.uid)
                    stationJob = observeLinkedStation(profile)
                    if (_state.value.pendingStationLink != null) resolvePendingStationLink()
                } else {
                    productJob?.cancel()
                    _state.update {
                        it.copy(
                            orders = emptyList(),
                            ordersFromCache = false,
                            ordersError = null,
                            cart = emptyList(),
                            savedAddresses = emptyList(),
                            stations = emptyList(),
                            stationsFromCache = false,
                            stationsError = null,
                            selectedStation = null,
                            products = emptyList(),
                            productsFromCache = false,
                            businessRulesRead = false,
                            businessRulesAccepted = false,
                            pendingStationSelection = null,
                        )
                    }
                }
            }
        }
    }

    private fun observeLinkedStation(profile: CustomerProfile): Job = viewModelScope.launch {
        val businessId = profile.preferredBusinessId
        val stationId = profile.preferredStationId
        if (businessId.isNullOrBlank() || stationId.isNullOrBlank()) {
            _state.update {
                it.copy(
                    stations = emptyList(),
                    selectedStation = null,
                    products = emptyList(),
                    cart = emptyList(),
                    stationsError = null,
                )
            }
            return@launch
        }
        catalogRepository.observeStation(businessId, stationId).collect { snapshot ->
            if (_state.value.profile?.uid == profile.uid) {
                _state.update { current ->
                    val refreshedSelectedStation = snapshot.items.singleOrNull()
                    // A direct document listener can emit an empty local-cache snapshot
                    // immediately after a QR link is saved. Keep the station we just
                    // resolved until Firestore has delivered an authoritative server result.
                    if (refreshedSelectedStation == null && snapshot.isFromCache && current.selectedStation != null) {
                        return@update current.copy(
                            stationsFromCache = true,
                            stationsError = snapshot.errorMessage,
                        )
                    }
                    val selectedStationRemoved = current.selectedStation != null &&
                        refreshedSelectedStation == null &&
                        snapshot.errorMessage == null
                    val rulesChanged = current.selectedStation?.businessRulesVersion !=
                        refreshedSelectedStation?.businessRulesVersion
                    current.copy(
                        stations = snapshot.items,
                        stationsFromCache = snapshot.isFromCache,
                        stationsError = snapshot.errorMessage,
                        selectedStation = refreshedSelectedStation,
                        products = if (selectedStationRemoved) emptyList() else current.products,
                        cart = if (selectedStationRemoved) emptyList() else current.cart,
                        message = if (selectedStationRemoved) {
                            "${current.selectedStation.name} is no longer available. Your cart was cleared."
                        } else current.message,
                        businessRulesRead = if (rulesChanged) false else current.businessRulesRead,
                        businessRulesAccepted = if (rulesChanged) false else current.businessRulesAccepted,
                    ).withValidPaymentMethod()
                }
                snapshot.items.singleOrNull()?.let { station ->
                    val selected = _state.value.selectedStation
                    if (selected == null || selected.id != station.id || selected.businessId != station.businessId) {
                        selectStation(station, persistPreference = false)
                    } else if (productJob == null) {
                        observeProductsFor(station)
                    }
                }
            }
        }
    }

    private fun restartLinkedStationObservation(profile: CustomerProfile) {
        stationJob?.cancel()
        stationJob = observeLinkedStation(profile)
    }

    fun retryLinkedStation() {
        val profile = _state.value.profile ?: return
        _state.update { it.copy(stationsError = null) }
        restartLinkedStationObservation(profile)
    }

    private fun observeOrders(uid: String): Job = viewModelScope.launch {
        orderRepository.observeOrders().collect { snapshot ->
            if (_state.value.profile?.uid == uid) {
                _state.update { it.copy(orders = snapshot.items, ordersFromCache = snapshot.isFromCache, ordersError = snapshot.errorMessage) }
            }
        }
    }

    fun retryOrders() {
        val uid = _state.value.profile?.uid ?: return
        orderJob?.cancel()
        _state.update { it.copy(ordersError = null) }
        orderJob = observeOrders(uid)
    }

    fun signInWithEmail(email: String, password: String) = viewModelScope.launch {
        _state.update { it.copy(authLoading = true, authError = null) }
        when (val result = authRepository.signInEmail(email, password)) {
            is AppResult.Success -> _state.update { it.copy(authLoading = false, profile = result.value) }
            is AppResult.Failure -> _state.update { it.copy(authLoading = false, authError = result.message) }
        }
    }

    fun registerWithEmail(name: String, email: String, password: String, confirmPassword: String) = viewModelScope.launch {
        if (password != confirmPassword) { _state.update { it.copy(authError = "Passwords do not match.") }; return@launch }
        _state.update { it.copy(authLoading = true, authError = null) }
        when (val result = authRepository.registerEmail(name, email, password)) {
            is AppResult.Success -> _state.update { it.copy(authLoading = false, profile = result.value, message = "Verification email sent.") }
            is AppResult.Failure -> _state.update { it.copy(authLoading = false, authError = result.message) }
        }
    }

    fun resetPassword(email: String) = viewModelScope.launch {
        _state.update { it.copy(authLoading = true, authError = null) }
        when (val result = authRepository.sendPasswordReset(email)) {
            is AppResult.Success -> _state.update { it.copy(authLoading = false, message = "Password reset email sent.") }
            is AppResult.Failure -> _state.update { it.copy(authLoading = false, authError = result.message) }
        }
    }

    fun resendEmailVerification() = viewModelScope.launch {
        when (val result = authRepository.resendEmailVerification()) {
            is AppResult.Success -> showMessage("Verification email sent again.")
            is AppResult.Failure -> showMessage(result.message)
        }
    }

    fun refreshEmailVerification() = viewModelScope.launch {
        when (val result = authRepository.refreshProfile()) {
            is AppResult.Success -> _state.update { it.copy(profile = result.value) }
            is AppResult.Failure -> showMessage(result.message)
        }
    }

    fun reauthenticateEmail(email: String, password: String, onSuccess: () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(authLoading = true, authError = null) }
        when (val result = authRepository.reauthenticateEmail(email, password)) {
            is AppResult.Success -> { _state.update { it.copy(authLoading = false) }; onSuccess() }
            is AppResult.Failure -> _state.update { it.copy(authLoading = false, authError = result.message) }
        }
    }

    fun reauthenticateGoogle(onSuccess: () -> Unit) = viewModelScope.launch {
        _state.update { it.copy(authLoading = true, authError = null) }
        when (val result = authRepository.reauthenticateGoogle()) {
            is AppResult.Success -> { _state.update { it.copy(authLoading = false) }; onSuccess() }
            is AppResult.Failure -> _state.update { it.copy(authLoading = false, authError = result.message) }
        }
    }

    fun completeProfile(name: String, phone: String, address: DeliveryAddress) = viewModelScope.launch {
        _state.update { it.copy(authLoading = true, authError = null) }
        when (val result = authRepository.updateProfile(name, phone, address)) {
            is AppResult.Success -> _state.update { it.copy(authLoading = false, profile = result.value, message = "Profile completed.") }
            is AppResult.Failure -> _state.update { it.copy(authLoading = false, authError = result.message) }
        }
    }

    fun signInWithGoogle() = viewModelScope.launch {
        if (_state.value.authLoading) return@launch
        _state.update { it.copy(authLoading = true, authError = null) }
        when (val result = authRepository.signInWithGoogle()) {
            is AppResult.Success -> _state.update { it.copy(authLoading = false, profile = result.value) }
            is AppResult.Failure -> _state.update { it.copy(authLoading = false, authError = result.message) }
        }
    }

    fun updateProfile(name: String, phone: String) = viewModelScope.launch {
        when (val result = authRepository.updateProfile(name, phone)) {
            is AppResult.Success -> _state.update { it.copy(profile = result.value, message = "Profile saved.") }
            is AppResult.Failure -> showMessage(result.message)
        }
    }

    fun signOut() = viewModelScope.launch {
        preferences?.clearPendingStationLink()
        _state.update { it.copy(pendingStationLink = null, pendingStationSelection = null) }
        authRepository.signOut()
    }

    fun handleIncomingLink(rawUri: String?) {
        val pending = PendingStationLink.parse(rawUri)
        if (pending == null) {
            showMessage("This AquaHub ordering link is invalid.")
            return
        }
        _state.update { it.copy(pendingStationLink = pending, resolvingStationLink = false, browsingDiscovery = false) }
        viewModelScope.launch { preferences?.setPendingStationLink(pending) }
        if (_state.value.profile != null) resolvePendingStationLink()
    }

    private fun resolvePendingStationLink() {
        if (pendingLinkJob?.isActive == true) return
        val pending = _state.value.pendingStationLink ?: return
        if (_state.value.profile == null) return
        pendingLinkJob = viewModelScope.launch {
            _state.update { it.copy(resolvingStationLink = true, stationsError = null) }
            when (val result = catalogRepository.resolveStation(pending.publicStationCode)) {
                is AppResult.Success -> {
                    if (shouldConfirmStationChange(result.value)) {
                        _state.update {
                            it.copy(
                                pendingStationSelection = result.value,
                                resolvingStationLink = false,
                                message = "Review the station switch before linking this QR code.",
                            )
                        }
                        return@launch
                    }
                    selectStation(result.value, persistPreference = false)
                    when (val saved = authRepository.setPreferredStation(PreferredStation(result.value.businessId, result.value.id, pending.source))) {
                        is AppResult.Success -> {
                            _state.update {
                                it.copy(
                                    profile = saved.value,
                                    stations = listOf(result.value),
                                    selectedStation = result.value,
                                    pendingStationLink = null,
                                    resolvingStationLink = false,
                                    browsingDiscovery = false,
                                    message = "Linked to ${result.value.name}.",
                                ).withValidPaymentMethod()
                            }
                            observeProductsFor(result.value)
                            restartLinkedStationObservation(saved.value)
                            preferences?.clearPendingStationLink()
                        }
                        is AppResult.Failure -> _state.update { it.copy(resolvingStationLink = false, message = saved.message) }
                    }
                }
                is AppResult.Failure -> {
                    _state.update { it.copy(resolvingStationLink = false, message = result.message) }
                }
            }
        }
    }

    fun retryPendingStationLink() = resolvePendingStationLink()

    fun setDiscoveryMode(enabled: Boolean) = _state.update { it.copy(browsingDiscovery = false) }

    fun clearAuthError() = _state.update { it.copy(authError = null) }

    fun deleteAccount() = viewModelScope.launch {
        _state.update { it.copy(deletingAccount = true, authError = null) }
        when (val result = authRepository.deleteAccount()) {
            is AppResult.Success -> {
                preferences?.clearPendingStationLink()
                _state.update { it.copy(deletingAccount = false, profile = null, pendingStationLink = null, pendingStationSelection = null, orders = emptyList(), cart = emptyList(), savedAddresses = emptyList(), message = "Your AquaHub account was deleted.") }
            }
            is AppResult.Failure -> _state.update { it.copy(deletingAccount = false, authError = result.message) }
        }
    }

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

    fun selectStation(
        station: PublicStation,
        persistPreference: Boolean = true,
        acquisitionSource: CustomerAcquisitionSource? = null,
    ) {
        val profile = _state.value.profile
        if (
            persistPreference &&
            profile != null &&
            (profile.preferredBusinessId != station.businessId || profile.preferredStationId != station.id)
        ) {
            showMessage("Scan this station's AquaHub QR code before switching stations.")
            return
        }
        if (_state.value.selectedStation?.let { it.id != station.id || it.businessId != station.businessId } == true && _state.value.cart.isNotEmpty()) {
            _state.update { it.copy(cart = emptyList(), message = "Cart cleared because you changed stations.") }
        }
        _state.update {
            it.copy(
                selectedStation = station,
                products = emptyList(),
                businessRulesRead = false,
                businessRulesAccepted = false
            ).withValidPaymentMethod()
        }
        observeProductsFor(station)
        if (persistPreference && _state.value.profile != null) {
            viewModelScope.launch {
                when (val result = authRepository.setPreferredStation(
                    PreferredStation(
                        businessId = station.businessId,
                        stationId = station.id,
                        acquisitionSource = acquisitionSource
                            ?: _state.value.profile?.acquisitionSource?.takeIf { it.isStationOwned }
                            ?: CustomerAcquisitionSource.STATION_QR,
                    ),
                )) {
                    is AppResult.Success -> _state.update { it.copy(profile = result.value) }
                    is AppResult.Failure -> showMessage(result.message)
                }
            }
        }
    }

    private fun observeProductsFor(station: PublicStation) {
        productJob?.cancel()
        productJob = viewModelScope.launch {
            catalogRepository.observeProducts(station.businessId, station.id).collect { snapshot ->
                _state.update { it.copy(products = snapshot.items, productsFromCache = snapshot.isFromCache) }
            }
        }
    }

    fun shouldConfirmStationChange(station: PublicStation): Boolean =
        _state.value.selectedStation?.let { it.id != station.id || it.businessId != station.businessId } == true &&
            _state.value.cart.isNotEmpty()

    fun requestStationChange(station: PublicStation) {
        _state.update { it.copy(pendingStationSelection = station) }
    }

    fun confirmStationChange() {
        val station = _state.value.pendingStationSelection ?: return
        val linkIsPending = _state.value.pendingStationLink != null
        _state.update {
            it.copy(
                pendingStationSelection = null,
                cart = emptyList(),
                message = "Cart cleared because you changed stations.",
            )
        }
        selectStation(station, persistPreference = !linkIsPending)
        if (linkIsPending) {
            viewModelScope.launch {
                pendingLinkJob?.join()
                resolvePendingStationLink()
            }
        }
    }

    fun cancelStationChange() {
        val linkIsPending = _state.value.pendingStationLink != null
        _state.update { it.copy(pendingStationSelection = null) }
        if (linkIsPending) {
            viewModelScope.launch {
                preferences?.clearPendingStationLink()
                _state.update { it.copy(pendingStationLink = null, message = "Station link cancelled. Your current cart was kept.") }
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

    fun setDeliveryMode(mode: DeliveryMode) = _state.update { current ->
        val changed = current.copy(deliveryMode = mode)
        changed.copy(
            selectedPaymentMethod = changed.selectedPaymentMethod
                ?.takeIf { it in changed.availablePaymentMethods }
                ?: changed.availablePaymentMethods.firstOrNull()
        ).withSelectedSavedAddressIfBlank()
    }
    fun setPaymentMethod(method: PaymentMethod) = _state.update {
        if (method in it.availablePaymentMethods) it.copy(selectedPaymentMethod = method) else it
    }
    fun setAddress(address: String) = _state.update { it.copy(address = address) }
    fun setCustomerNote(note: String) = _state.update { it.copy(customerNote = note.take(500)) }

    fun setBusinessRulesRead(read: Boolean) = _state.update {
        if (read) it.copy(businessRulesRead = true) else it
    }

    fun setBusinessRulesAccepted(accepted: Boolean) = _state.update {
        it.copy(businessRulesAccepted = accepted && it.businessRulesRead)
    }

    fun prepareCheckout() = _state.update {
        it.withSelectedSavedAddressIfBlank().copy(
            // Consent is specific to this checkout attempt. Returning to the cart and
            // opening checkout again must require a fresh acknowledgement.
            businessRulesRead = false,
            businessRulesAccepted = false,
        )
    }

    fun submitOrder(onCreated: (CustomerOrder) -> Unit) {
        val state = _state.value
        if (state.submitting) {
            showMessage("Your order is already being submitted.")
            return
        }
        val station = state.selectedStation ?: return showMessage("Select a station.")
        val profile = state.profile ?: return showMessage("Sign in before placing an order.")
        if (station.businessId != profile.preferredBusinessId || station.id != profile.preferredStationId) {
            return showMessage("Scan the station QR code before ordering.")
        }
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
        if (profile.phone.isBlank()) return showMessage("Add your phone number in Account before ordering.")
        val request = CreateOrderRequest(
            businessId = station.businessId,
            stationId = station.id,
            items = state.cart,
            deliveryAddress = if (state.deliveryMode == DeliveryMode.DELIVERY) {
                DeliveryAddress("Delivery", state.address, state.location, state.addressPlaceId)
            } else null,
            deliveryMode = state.deliveryMode,
            paymentMethod = state.selectedPaymentMethod
                ?: return showMessage("Choose an available payment method."),
            customerNote = state.customerNote.ifBlank { null },
            acceptedBusinessRulesVersion = station.businessRulesVersion,
        )
        request.validate().firstOrNull()?.let { return showMessage(it) }
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            when (val result = orderRepository.createOrder(request)) {
                is AppResult.Success -> {
                    _state.update {
                        it.copy(
                            submitting = false,
                            orders = listOf(result.value) + it.orders.filterNot { order -> order.id == result.value.id },
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
                savedAddressDao.insert(address.toEntity(requireNotNull(_state.value.profile).uid))
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
                savedAddressDao.deleteByAddressLine(requireNotNull(_state.value.profile).uid, addressToDelete.addressLine)
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
        val station = _state.value.preferredStation?.takeIf { it.id == order.stationId }
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
    fun showMessage(message: String) = _state.update { it.copy(message = message) }
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

internal fun CustomerUiState.withValidPaymentMethod(): CustomerUiState = copy(
    selectedPaymentMethod = selectedPaymentMethod
        ?.takeIf { it in availablePaymentMethods }
        ?: availablePaymentMethods.firstOrNull()
)
