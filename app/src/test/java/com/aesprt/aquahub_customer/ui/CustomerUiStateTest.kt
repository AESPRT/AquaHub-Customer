package com.aesprt.aquahub_customer.ui

import com.aesprt.aquahub_customer.domain.DeliveryAddress
import com.aesprt.aquahub_customer.domain.CustomerAcquisitionSource
import com.aesprt.aquahub_customer.domain.CustomerProfile
import com.aesprt.aquahub_customer.domain.DeliveryMode
import com.aesprt.aquahub_customer.domain.GeoPoint
import com.aesprt.aquahub_customer.domain.Money
import com.aesprt.aquahub_customer.domain.PaymentMethod
import com.aesprt.aquahub_customer.domain.PublicStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class CustomerUiStateTest {
    @Test
    fun `selected saved address fills blank delivery address`() {
        val savedLocation = GeoPoint(8.48, 124.65)
        val state = CustomerUiState(
            savedAddresses = listOf(
                DeliveryAddress("Home", "First address"),
                DeliveryAddress("Work", "Second address", savedLocation, "saved-place"),
            ),
            selectedAddressIndex = 1,
        )

        val prepared = state.withSelectedSavedAddressIfBlank()

        assertEquals("Second address", prepared.address)
        assertEquals(savedLocation, prepared.location)
        assertEquals("saved-place", prepared.addressPlaceId)
        assertEquals(1, prepared.selectedAddressIndex)
    }

    @Test
    fun `saved address does not overwrite address already entered`() {
        val state = CustomerUiState(
            address = "Address entered at checkout",
            savedAddresses = listOf(DeliveryAddress("Home", "Saved address")),
        )

        val prepared = state.withSelectedSavedAddressIfBlank()

        assertEquals("Address entered at checkout", prepared.address)
    }

    @Test
    fun `pickup does not fill a saved delivery address`() {
        val state = CustomerUiState(
            deliveryMode = DeliveryMode.PICKUP,
            savedAddresses = listOf(DeliveryAddress("Home", "Saved address")),
        )

        val prepared = state.withSelectedSavedAddressIfBlank()

        assertEquals("", prepared.address)
        assertNull(prepared.location)
    }

    @Test
    fun `nearest filter returns empty list when customer location is null`() {
        val state = CustomerUiState(
            location = null,
            stationFilter = StationFilter.NEAREST,
            stations = listOf(
                station("Near", GeoPoint(8.451, 124.631)),
            ),
        )

        assertEquals(emptyList<PublicStation>(), state.displayedStations(LocalTime.NOON))
    }

    @Test
    fun `linked customer sees only exact scanned station`() {
        val state = CustomerUiState(
            profile = CustomerProfile(
                uid = "customer",
                displayName = "Customer",
                email = null,
                phone = "",
                preferredBusinessId = "business-a",
                preferredStationId = "branch-a",
                acquisitionSource = CustomerAcquisitionSource.STATION_QR,
            ),
            stations = listOf(
                station("A", GeoPoint(8.45, 124.63)).copy(id = "branch-a", businessId = "business-a"),
                station("B", GeoPoint(8.46, 124.64)).copy(id = "branch-b", businessId = "business-a"),
                station("Competitor", GeoPoint(8.451, 124.631)).copy(id = "competitor", businessId = "business-b"),
            ),
        )

        assertEquals(listOf("A"), state.displayedStations(LocalTime.NOON).map { it.name })
        assertEquals(
            listOf("A"),
            state.copy(browsingDiscovery = true).displayedStations(LocalTime.NOON).map { it.name },
        )
    }

    @Test
    fun `unlinked customer cannot browse any station`() {
        val state = CustomerUiState(
            profile = CustomerProfile(
                uid = "customer",
                displayName = "Customer",
                email = null,
                phone = "",
                acquisitionSource = CustomerAcquisitionSource.ORGANIC_APP,
            ),
            stations = listOf(
                station("A", GeoPoint(8.45, 124.63)).copy(businessId = "business-a"),
                station("Competitor", GeoPoint(8.451, 124.631)).copy(businessId = "business-b"),
            ),
        )

        assertEquals(emptyList<PublicStation>(), state.displayedStations(LocalTime.NOON))
    }

    @Test
    fun `checkout requires published station rules and explicit acceptance`() {
        val stationWithRules = station("Station", GeoPoint(8.45, 124.63)).copy(
            businessRulesText = "Return borrowed containers in good condition.",
            businessRulesVersion = 3L,
        )

        assertFalse(CustomerUiState(selectedStation = stationWithRules).canPlaceOrder)
        assertTrue(
            CustomerUiState(
                selectedStation = stationWithRules,
                businessRulesRead = true,
                businessRulesAccepted = true,
            ).canPlaceOrder,
        )
        assertFalse(
            CustomerUiState(
                selectedStation = stationWithRules.copy(businessRulesText = null),
                businessRulesRead = true,
                businessRulesAccepted = true,
            ).canPlaceOrder,
        )
    }

    @Test
    fun `checkout exposes only station enabled methods for fulfilment mode`() {
        val configured = station("Station", GeoPoint(8.45, 124.63)).copy(
            cashPaymentEnabled = false,
            codPaymentEnabled = true,
            gcashPaymentEnabled = true,
            mayaPaymentEnabled = false,
        )

        assertEquals(
            listOf(PaymentMethod.CASH_ON_DELIVERY, PaymentMethod.GCASH),
            CustomerUiState(selectedStation = configured, deliveryMode = DeliveryMode.DELIVERY).availablePaymentMethods,
        )
        assertEquals(
            listOf(PaymentMethod.GCASH),
            CustomerUiState(selectedStation = configured, deliveryMode = DeliveryMode.PICKUP).availablePaymentMethods,
        )
    }

    private fun station(
        name: String,
        point: GeoPoint,
        opening: String? = null,
        closing: String? = null,
        accepting: Boolean = true,
        open: Boolean = true,
    ) = PublicStation(
        id = name,
        businessId = "business",
        name = name,
        phone = "",
        address = name,
        location = point,
        isAcceptingOrders = accepting,
        openingTime = opening,
        closingTime = closing,
        deliveryRadiusKm = 5.0,
        deliveryFee = Money.Zero,
        estimatedPreparationMinutes = 15,
        isOpen = open,
    )
}
