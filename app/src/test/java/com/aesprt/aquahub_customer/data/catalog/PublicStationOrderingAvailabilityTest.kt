package com.aesprt.aquahub_customer.data.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicStationOrderingAvailabilityTest {
    @Test
    fun `only a matching affirmative RPC row enables customer ordering`() {
        assertTrue(confirmedOrderingAvailability("station-a", mapOf("station-a" to true)))
        assertFalse(confirmedOrderingAvailability("station-a", mapOf("station-b" to true)))
        assertFalse(confirmedOrderingAvailability("station-a", mapOf("station-a" to false)))
        assertFalse(confirmedOrderingAvailability("station-a", emptyMap()))
    }
}
