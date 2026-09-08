package com.aesprt.aquahub_customer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class ModelsTest {
    @Test
    fun `money uses exact centavo arithmetic`() {
        val line = CartLine(
            PublicProduct("water", "Water", ProductType.REFILL, "5 Gallon", "", null, Money(3510), true),
            3,
        )

        assertEquals(10_530, line.subtotal.centavos)
        assertEquals(15_530, (line.subtotal + Money(5000)).centavos)
    }

    @Test
    fun `distance is symmetric and close to known value`() {
        val station = GeoPoint(8.4803, 124.6498)
        val customer = GeoPoint(8.4542, 124.6508)

        assertEquals(station.distanceKmTo(customer), customer.distanceKmTo(station), 0.0001)
        assertEquals(2.9, station.distanceKmTo(customer), 0.2)
    }

    @Test
    fun `customer order request requires address only for delivery`() {
        val item = CartLine(PublicProduct("p", "Water", ProductType.REFILL, "20 L", "", null, Money(3000), true), 1)
        val delivery = CreateOrderRequest("business", "station", listOf(item), null, DeliveryMode.DELIVERY, PaymentMethod.CASH_ON_DELIVERY, null)
        val pickup = CreateOrderRequest("business", "station", listOf(item), null, DeliveryMode.PICKUP, PaymentMethod.CASH, null)

        assertTrue(delivery.validate().any { it.contains("address") })
        assertTrue(pickup.validate().isEmpty())
    }

    @Test
    fun `order state machine matches owner contract`() {
        assertTrue(OrderStatus.PENDING.allowedTransitions().contains(OrderStatus.ACCEPTED))
        assertTrue(OrderStatus.PENDING.allowedTransitions().contains(OrderStatus.CANCELLED))
        assertFalse(OrderStatus.OUT_FOR_DELIVERY.allowedTransitions().contains(OrderStatus.CANCELLED))
        assertTrue(OrderStatus.COMPLETED.isTerminal)
    }

    @Test
    fun `station open now handles daytime and overnight schedules`() {
        val daytime = station(openingTime = "8:00 AM", closingTime = "6:00 PM")
        val overnight = station(openingTime = "20:00", closingTime = "04:00")

        assertTrue(daytime.isOpenAt(LocalTime.of(10, 0)))
        assertFalse(daytime.isOpenAt(LocalTime.of(19, 0)))
        assertTrue(overnight.isOpenAt(LocalTime.of(23, 0)))
        assertTrue(overnight.isOpenAt(LocalTime.of(2, 0)))
        assertFalse(overnight.isOpenAt(LocalTime.of(12, 0)))
    }

    @Test
    fun `station without schedule follows accepting orders state`() {
        assertTrue(station(openingTime = null, closingTime = null).isOpenAt(LocalTime.NOON))
        assertFalse(
            station(openingTime = null, closingTime = null, isAcceptingOrders = false)
                .isOpenAt(LocalTime.NOON),
        )
    }

    private fun station(
        openingTime: String?,
        closingTime: String?,
        isAcceptingOrders: Boolean = true,
    ) = PublicStation(
        id = "station",
        businessId = "business",
        name = "Station",
        phone = "",
        address = "",
        location = GeoPoint(8.45, 124.63),
        isAcceptingOrders = isAcceptingOrders,
        openingTime = openingTime,
        closingTime = closingTime,
        deliveryRadiusKm = 5.0,
        deliveryFee = Money.Zero,
        estimatedPreparationMinutes = 15,
    )
}
