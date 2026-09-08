package com.aesprt.aquahub_customer.ui

import com.aesprt.aquahub_customer.domain.DeliveryAddress
import com.aesprt.aquahub_customer.domain.DeliveryMode
import com.aesprt.aquahub_customer.domain.GeoPoint
import com.aesprt.aquahub_customer.domain.Money
import com.aesprt.aquahub_customer.domain.PublicStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `nearest filter only includes stations within 1km from selected customer location`() {
        val state = CustomerUiState(
            location = GeoPoint(8.45, 124.63),
            stationFilter = StationFilter.NEAREST,
            stations = listOf(
                station("Far", GeoPoint(8.60, 124.80)), // ~25 km away
                station("Near", GeoPoint(8.451, 124.631)), // ~0.15 km away
            ),
        )

        assertEquals(listOf("Near"), state.displayedStations(LocalTime.NOON).map { it.name })
    }

    @Test
    fun `nearest filter sorts multiple stations within 1km closest first`() {
        val state = CustomerUiState(
            location = GeoPoint(8.45, 124.63),
            stationFilter = StationFilter.NEAREST,
            stations = listOf(
                station("Far", GeoPoint(8.60, 124.80)), // ~25 km away (excluded)
                station("Mid", GeoPoint(8.455, 124.635)), // ~0.78 km away
                station("Closest", GeoPoint(8.451, 124.631)), // ~0.15 km away
            ),
        )

        assertEquals(listOf("Closest", "Mid"), state.displayedStations(LocalTime.NOON).map { it.name })
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
    fun `open now filter excludes closed and paused stations`() {
        val state = CustomerUiState(
            stationFilter = StationFilter.OPEN_NOW,
            stations = listOf(
                station("Open", GeoPoint(8.45, 124.63), opening = "08:00", closing = "18:00"),
                station("Closed", GeoPoint(8.46, 124.64), opening = "18:00", closing = "23:00"),
                station("Paused", GeoPoint(8.47, 124.65), accepting = false),
            ),
        )

        assertEquals(listOf("Open"), state.displayedStations(LocalTime.of(10, 0)).map { it.name })
    }

    private fun station(
        name: String,
        point: GeoPoint,
        opening: String? = null,
        closing: String? = null,
        accepting: Boolean = true,
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
    )
}
