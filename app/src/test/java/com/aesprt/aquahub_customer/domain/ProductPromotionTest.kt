package com.aesprt.aquahub_customer.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductPromotionTest {
    @Test
    fun `percentage promotion rounds to centavos`() {
        val product = PublicProduct(
            id = "p1",
            name = "Refill",
            type = ProductType.REFILL,
            sizeLabel = "20L",
            description = "",
            imagePath = null,
            price = Money(3_500),
            isAvailable = true,
            promotion = PublicPromotion(
                label = "Ten percent off",
                type = PublicPromotionType.PERCENTAGE,
                percentBps = 1_000,
            ),
        )

        assertEquals(Money(3_150), product.unitPriceFor(1))
    }

    @Test
    fun `quantity break applies only at threshold`() {
        val product = PublicProduct(
            id = "p1",
            name = "Refill",
            type = ProductType.REFILL,
            sizeLabel = "20L",
            description = "",
            imagePath = null,
            price = Money(4_000),
            isAvailable = true,
            promotion = PublicPromotion(
                label = "Buy three",
                type = PublicPromotionType.QUANTITY_BREAK,
                percentBps = 2_500,
                minimumQuantity = 3,
            ),
        )

        assertEquals(Money(4_000), product.unitPriceFor(2))
        assertEquals(Money(3_000), product.unitPriceFor(3))
        assertEquals(Money(9_000), CartLine(product, 3).subtotal)
    }

    @Test
    fun `expired promotion never applies`() {
        val product = PublicProduct(
            id = "p1",
            name = "Refill",
            type = ProductType.REFILL,
            sizeLabel = "20L",
            description = "",
            imagePath = null,
            price = Money(4_000),
            isAvailable = true,
            promotion = PublicPromotion(
                label = "Expired",
                type = PublicPromotionType.FIXED_PRICE,
                promotionalPrice = Money(2_000),
                endsAt = 1L,
            ),
        )

        assertEquals(Money(4_000), product.unitPriceFor(1))
    }
}
