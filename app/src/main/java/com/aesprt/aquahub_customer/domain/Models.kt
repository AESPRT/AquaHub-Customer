package com.aesprt.aquahub_customer.domain

import java.text.NumberFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@JvmInline
value class Money(val centavos: Long) {
    init { require(centavos >= 0) }

    operator fun plus(other: Money) = Money(Math.addExact(centavos, other.centavos))
    operator fun times(quantity: Int): Money {
        require(quantity >= 0)
        return Money(Math.multiplyExact(centavos, quantity.toLong()))
    }

    fun format(): String = NumberFormat.getCurrencyInstance(Locale("en", "PH"))
        .format(centavos / 100.0)

    companion object {
        val Zero = Money(0)
        val ZERO = Zero
        fun fromPesos(pesos: Long): Money = Money(pesos * 100L)
        fun fromPesos(pesos: Int): Money = Money(pesos.toLong() * 100L)
    }
}

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0)
        require(longitude in -180.0..180.0)
    }

    fun distanceKmTo(other: GeoPoint): Double {
        val earthRadiusKm = 6371.0088
        val latDelta = Math.toRadians(other.latitude - latitude)
        val lonDelta = Math.toRadians(other.longitude - longitude)
        val a = sin(latDelta / 2).pow(2) +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) *
            sin(lonDelta / 2).pow(2)
        return 2 * earthRadiusKm * asin(sqrt(a))
    }
}

data class LocationSuggestion(
    val placeId: String,
    val primaryText: String,
    val secondaryText: String? = null,
)

data class ResolvedLocation(
    val placeId: String? = null,
    val formattedAddress: String,
    val point: GeoPoint,
)

data class CustomerProfile(
    val uid: String,
    val displayName: String,
    val email: String?,
    val phone: String,
    val photoUrl: String? = null,
    val address: DeliveryAddress? = null,
    val setupComplete: Boolean = false,
    val emailVerified: Boolean = false,
    val preferredBusinessId: String? = null,
    val preferredStationId: String? = null,
    val acquisitionSource: CustomerAcquisitionSource = CustomerAcquisitionSource.UNKNOWN,
    val acquiredAt: Long? = null,
)

enum class CustomerAcquisitionSource {
    STATION_QR,
    STATION_LINK,
    OWNER_REFERRAL,
    ORGANIC_APP,
    SEARCH,
    UNKNOWN;

    val isStationOwned: Boolean
        get() = this == STATION_QR || this == STATION_LINK || this == OWNER_REFERRAL

    companion object {
        fun fromString(value: String?): CustomerAcquisitionSource =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

data class PendingStationLink(
    val publicStationCode: String,
    val source: CustomerAcquisitionSource = CustomerAcquisitionSource.STATION_LINK,
) {
    companion object {
        private val route = Regex("^/s/([A-Za-z0-9_-]{6,64})/?$")
        private val supportedHosts = setOf("aquahub.aesprt.com", "order.aquahub.app")

        fun parse(rawUri: String?): PendingStationLink? {
            val normalized = rawUri.orEmpty()
                .trim()
                .replace("\u200B", "")
                .removePrefix("\uFEFF")
            if (Regex("^[A-Za-z0-9_-]{6,64}$").matches(normalized)) {
                return PendingStationLink(normalized, CustomerAcquisitionSource.STATION_QR)
            }
            val uri = runCatching { java.net.URI(normalized) }.getOrNull() ?: return null
            if (uri.scheme.lowercase(Locale.US) != "https" || uri.host.lowercase(Locale.US) !in supportedHosts) return null
            val path = uri.path ?: return null
            val match = route.matchEntire(path) ?: return null
            val source = when (uri.rawQuery.orEmpty().split('&')
                .firstOrNull { it.substringBefore('=').equals("source", ignoreCase = true) }
                ?.substringAfter('=', "")?.uppercase(Locale.US)) {
                "QR", "STATION_QR" -> CustomerAcquisitionSource.STATION_QR
                "REFERRAL", "OWNER_REFERRAL" -> CustomerAcquisitionSource.OWNER_REFERRAL
                else -> CustomerAcquisitionSource.STATION_LINK
            }
            return PendingStationLink(match.groupValues[1], source)
        }
    }
}

data class DeliveryAddress(
    val label: String,
    val addressLine: String,
    val location: GeoPoint? = null,
    val placeId: String? = null,
)

data class PublicStation(
    val id: String,
    val businessId: String,
    val name: String,
    val phone: String,
    val address: String,
    val location: GeoPoint?,
    val isAcceptingOrders: Boolean,
    val openingTime: String?,
    val closingTime: String?,
    val deliveryRadiusKm: Double,
    val deliveryFee: Money,
    val estimatedPreparationMinutes: Int,
    val isOpen: Boolean = true,
    val manualOpenOverride: Boolean = false,
    val logoPath: String? = null,
    val isFromCache: Boolean = false,
    val averageRating: Float? = null,
    val ratingCount: Int = 0,
    val businessRulesText: String? = null,
    val businessRulesVersion: Long = 0L,
    val publicStationCode: String? = null,
    val cashPaymentEnabled: Boolean = true,
    val codPaymentEnabled: Boolean = true,
    val gcashPaymentEnabled: Boolean = false,
    val gcashAccountName: String? = null,
    val gcashAccountNumber: String? = null,
    val gcashQrImagePath: String? = null,
    val mayaPaymentEnabled: Boolean = false,
    val mayaAccountName: String? = null,
    val mayaAccountNumber: String? = null,
    val mayaQrImagePath: String? = null,
) {
    fun distanceKmFrom(origin: GeoPoint?): Double? =
        if (origin == null || location == null) null else origin.distanceKmTo(location)

    fun isOpenAt(time: LocalTime): Boolean {
        if (!isOpen || !isAcceptingOrders) return false
        if (manualOpenOverride) return true
        val opens = openingTime.toStationTimeOrNull() ?: return true
        val closes = closingTime.toStationTimeOrNull() ?: return true
        if (opens == closes) return true
        return if (opens < closes) {
            !time.isBefore(opens) && time.isBefore(closes)
        } else {
            !time.isBefore(opens) || time.isBefore(closes)
        }
    }
}

private fun String?.toStationTimeOrNull(): LocalTime? {
    val value = this?.trim().orEmpty()
    if (value.isEmpty()) return null
    val formatters = listOf(
        DateTimeFormatter.ofPattern("H:mm"),
        DateTimeFormatter.ofPattern("h:mm a", Locale.US),
        DateTimeFormatter.ofPattern("h a", Locale.US),
    )
    return formatters.firstNotNullOfOrNull { formatter ->
        try {
            LocalTime.parse(value.uppercase(Locale.US), formatter)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

enum class ProductType(val label: String) {
    REFILL("Refills"),
    NEW_CONTAINER("New containers"),
    EMPTY_CONTAINER("Container returns"),
    OTHER("Others"),
}

data class PublicProduct(
    val id: String,
    val name: String,
    val type: ProductType,
    val sizeLabel: String,
    val description: String,
    val imagePath: String?,
    val price: Money,
    val isAvailable: Boolean,
    val promotion: PublicPromotion? = null,
)

enum class PublicPromotionType { PERCENTAGE, FIXED_PRICE, QUANTITY_BREAK }

data class PublicPromotion(
    val label: String,
    val type: PublicPromotionType,
    val percentBps: Int? = null,
    val promotionalPrice: Money? = null,
    val minimumQuantity: Int = 1,
    val startsAt: Long = 0L,
    val endsAt: Long? = null,
) {
    fun isLive(now: Long = System.currentTimeMillis()): Boolean =
        startsAt <= now && (endsAt == null || endsAt > now)

    fun applies(quantity: Int, now: Long = System.currentTimeMillis()): Boolean =
        quantity >= minimumQuantity && isLive(now)
}

fun PublicProduct.unitPriceFor(quantity: Int): Money {
    val offer = promotion?.takeIf { it.applies(quantity) } ?: return price
    return when (offer.type) {
        PublicPromotionType.FIXED_PRICE -> offer.promotionalPrice ?: price
        PublicPromotionType.PERCENTAGE, PublicPromotionType.QUANTITY_BREAK -> {
            val bps = offer.percentBps ?: return price
            Money((price.centavos * (10_000L - bps) + 5_000L) / 10_000L)
        }
    }
}

data class CartLine(val product: PublicProduct, val quantity: Int) {
    init { require(quantity in 1..99) }
    val subtotal: Money get() = product.unitPriceFor(quantity) * quantity
}

enum class DeliveryMode { DELIVERY, PICKUP }
enum class PaymentMethod(val label: String) {
    CASH_ON_DELIVERY("Cash on Delivery"),
    CASH("Cash on Pickup"),
    GCASH("GCash"),
    MAYA("Maya")
}

enum class OrderStatus(val label: String) {
    PENDING("Pending"),
    ACCEPTED("Accepted"),
    PREPARING("Preparing"),
    READY_FOR_PICKUP("Ready for pickup"),
    RIDER_ASSIGNED("Rider assigned"),
    OUT_FOR_DELIVERY("Out for delivery"),
    DELIVERED("Delivered"),
    COMPLETED("Completed"),
    REJECTED("Rejected"),
    CANCELLED("Cancelled");

    fun allowedTransitions(): Set<OrderStatus> = when (this) {
        PENDING -> setOf(ACCEPTED, REJECTED, CANCELLED)
        ACCEPTED -> setOf(PREPARING, CANCELLED)
        PREPARING -> setOf(READY_FOR_PICKUP, CANCELLED)
        READY_FOR_PICKUP -> setOf(RIDER_ASSIGNED, COMPLETED)
        RIDER_ASSIGNED -> setOf(OUT_FOR_DELIVERY)
        OUT_FOR_DELIVERY -> setOf(DELIVERED)
        DELIVERED -> setOf(COMPLETED)
        COMPLETED, REJECTED, CANCELLED -> emptySet()
    }

    val isTerminal: Boolean get() = this in setOf(COMPLETED, REJECTED, CANCELLED)
}

data class CustomerOrder(
    val id: String,
    val orderNumber: String,
    val stationId: String,
    val stationName: String,
    val items: List<CartLine>,
    val subtotal: Money,
    val deliveryFee: Money,
    val discount: Money,
    val total: Money,
    val status: OrderStatus,
    val deliveryMode: DeliveryMode,
    val paymentMethod: PaymentMethod = if (deliveryMode == DeliveryMode.DELIVERY) PaymentMethod.CASH_ON_DELIVERY else PaymentMethod.CASH,
    val deliveryAddress: String,
    val deliveryLocation: GeoPoint? = null,
    val customerNote: String?,
    val rejectionReason: String?,
    val requestedAt: Long,
    val updatedAt: Long,
)

data class CreateOrderRequest(
    val businessId: String,
    val stationId: String,
    val items: List<CartLine>,
    val deliveryAddress: DeliveryAddress?,
    val deliveryMode: DeliveryMode,
    val paymentMethod: PaymentMethod,
    val customerNote: String?,
    val acceptedBusinessRulesVersion: Long? = null,
    val idempotencyKey: String = UUID.randomUUID().toString(),
) {
    fun validate(): List<String> = buildList {
        if (businessId.isBlank()) add("The station business is unavailable.")
        if (stationId.isBlank()) add("Select a station.")
        if (items.isEmpty()) add("Your cart is empty.")
        if (items.any { it.quantity !in 1..99 }) add("Item quantity must be between 1 and 99.")
        if (deliveryMode == DeliveryMode.DELIVERY && deliveryAddress?.addressLine.isNullOrBlank()) {
            add("Enter a delivery address.")
        }
        if (deliveryMode == DeliveryMode.DELIVERY && deliveryAddress?.location == null) {
            add("Select the delivery address from the map.")
        }
        if (customerNote != null && customerNote.length > 500) add("Note is too long.")
    }
}

data class CatalogSnapshot<T>(
    val items: List<T>,
    val isFromCache: Boolean,
    val errorMessage: String? = null,
)

sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val message: String, val recoverable: Boolean = true) : AppResult<Nothing>
}
