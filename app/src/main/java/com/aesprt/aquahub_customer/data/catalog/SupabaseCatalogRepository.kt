package com.aesprt.aquahub_customer.data.catalog

import com.aesprt.aquahub_customer.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub_customer.domain.AppResult
import com.aesprt.aquahub_customer.domain.CatalogRepository
import com.aesprt.aquahub_customer.domain.CatalogSnapshot
import com.aesprt.aquahub_customer.domain.GeoPoint
import com.aesprt.aquahub_customer.domain.Money
import com.aesprt.aquahub_customer.domain.ProductType
import com.aesprt.aquahub_customer.domain.PublicProduct
import com.aesprt.aquahub_customer.domain.PublicPromotion
import com.aesprt.aquahub_customer.domain.PublicPromotionType
import com.aesprt.aquahub_customer.domain.PublicStation
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Public customer catalog backed by Supabase projection tables.
 *
 * The legacy Firebase identifiers are lookup metadata only. They keep existing
 * QR links and customer preferences compatible while the domain authority is
 * the UUID-backed Supabase row.
 */
class SupabaseCatalogRepository : CatalogRepository {
    private val supabase get() = SupabaseClientProvider.client

    override fun observeStation(
        businessId: String,
        stationId: String,
    ): Flow<CatalogSnapshot<PublicStation>> = flow {
        while (true) {
            runCatching { findStation(businessId, stationId) }
                .onSuccess { station ->
                    emit(CatalogSnapshot(listOfNotNull(station), isFromCache = false))
                }
                .onFailure { error ->
                    emit(CatalogSnapshot(emptyList(), isFromCache = false, errorMessage = error.safeMessage("station")))
                }
            delay(POLL_INTERVAL_MS)
        }
    }

    override fun observeProducts(
        businessId: String,
        stationId: String,
    ): Flow<CatalogSnapshot<PublicProduct>> = flow {
        while (true) {
            runCatching { loadProducts(stationId) }
                .onSuccess { products -> emit(CatalogSnapshot(products, isFromCache = false)) }
                .onFailure { error ->
                    emit(CatalogSnapshot(emptyList(), isFromCache = false, errorMessage = error.safeMessage("products")))
                }
            delay(POLL_INTERVAL_MS)
        }
    }

    override suspend fun resolveStation(publicStationCode: String): AppResult<PublicStation> {
        val code = publicStationCode.trim()
        if (!Regex("[A-Za-z0-9_-]{6,64}").matches(code)) {
            return AppResult.Failure("This ordering link is invalid.", recoverable = false)
        }
        return runCatching {
            val row = supabase.from("public_stations").select {
                filter { eq("public_station_code", code) }
            }.decodeList<PublicStationRow>().firstOrNull()
                ?: error("This station ordering link is no longer available.")
            AppResult.Success(row.toDomain(loadOrderingAvailability(row.stationId)))
        }.getOrElse { error ->
            AppResult.Failure(error.safeMessage("station link"))
        }
    }

    private suspend fun findStation(businessId: String, stationId: String): PublicStation? {
        val query = supabase.from("public_stations").select {
            filter {
                if (stationId.isUuid()) eq("station_id", stationId)
                else eq("station_firebase_id", stationId)
            }
        }
        val row = query.decodeList<PublicStationRow>().firstOrNull()?.takeIf {
            businessId.isBlank() || businessId == it.organizationFirebaseId || businessId == it.organizationId
        } ?: return null
        return row.toDomain(loadOrderingAvailability(row.stationId))
    }

    private suspend fun loadOrderingAvailability(stationId: String): Boolean {
        val rows = try {
            supabase.postgrest.rpc(
                function = "get_public_station_ordering_eligibility",
                parameters = buildJsonObject {
                    put("p_station_ids", buildJsonArray { add(stationId) })
                },
            ).decodeList<PublicStationOrderingAvailabilityRow>()
        } catch (error: Exception) {
            throw IllegalStateException("Ordering availability could not be confirmed. Check your connection and try again.", error)
        }
        return confirmedOrderingAvailability(stationId, rows.associate { it.stationId to it.isOrderingAvailable })
    }

    private suspend fun loadProducts(stationId: String): List<PublicProduct> {
        val rows = supabase.from("public_products").select {
            filter {
                if (stationId.isUuid()) eq("station_id", stationId)
                else eq("station_firebase_id", stationId)
                eq("is_available_for_ordering", true)
            }
        }.decodeList<PublicProductRow>()
        return rows.map(PublicProductRow::toDomain)
    }

    @Serializable
    private data class PublicStationRow(
        @SerialName("station_id") val stationId: String,
        @SerialName("organization_id") val organizationId: String,
        @SerialName("station_firebase_id") val stationFirebaseId: String? = null,
        @SerialName("organization_firebase_id") val organizationFirebaseId: String? = null,
        @SerialName("public_station_code") val publicStationCode: String? = null,
        val name: String,
        val phone: String,
        val address: String,
        val latitude: Double? = null,
        val longitude: Double? = null,
        @SerialName("logo_path") val logoPath: String? = null,
        @SerialName("opening_time") val openingTime: String? = null,
        @SerialName("closing_time") val closingTime: String? = null,
        @SerialName("is_open") val isOpen: Boolean,
        @SerialName("manual_open_override") val manualOpenOverride: Boolean,
        @SerialName("is_accepting_orders") val isAcceptingOrders: Boolean,
        @SerialName("delivery_radius_km") val deliveryRadiusKm: Double = 0.0,
        @SerialName("delivery_fee_centavos") val deliveryFeeCentavos: Long = 0,
        @SerialName("estimated_preparation_minutes") val estimatedPreparationMinutes: Int = 30,
        @SerialName("business_rules_text") val businessRulesText: String? = null,
        @SerialName("business_rules_version") val businessRulesVersion: Long = 0,
        @SerialName("cash_payment_enabled") val cashPaymentEnabled: Boolean = true,
        @SerialName("cod_payment_enabled") val codPaymentEnabled: Boolean = true,
        @SerialName("gcash_payment_enabled") val gcashPaymentEnabled: Boolean = false,
        @SerialName("gcash_account_name") val gcashAccountName: String? = null,
        @SerialName("gcash_account_number") val gcashAccountNumber: String? = null,
        @SerialName("gcash_qr_image_path") val gcashQrImagePath: String? = null,
        @SerialName("maya_payment_enabled") val mayaPaymentEnabled: Boolean = false,
        @SerialName("maya_account_name") val mayaAccountName: String? = null,
        @SerialName("maya_account_number") val mayaAccountNumber: String? = null,
        @SerialName("maya_qr_image_path") val mayaQrImagePath: String? = null,
    ) {
        fun toDomain(orderingAvailable: Boolean) = PublicStation(
            id = stationFirebaseId ?: stationId,
            businessId = organizationFirebaseId ?: organizationId,
            name = name,
            phone = phone,
            address = address,
            location = latitude?.let { lat -> longitude?.let { lon -> GeoPoint(lat, lon) } },
            isAcceptingOrders = orderingAvailable,
            openingTime = openingTime,
            closingTime = closingTime,
            deliveryRadiusKm = deliveryRadiusKm,
            deliveryFee = Money(deliveryFeeCentavos),
            estimatedPreparationMinutes = estimatedPreparationMinutes,
            isOpen = isOpen,
            manualOpenOverride = manualOpenOverride,
            logoPath = logoPath,
            businessRulesText = businessRulesText,
            businessRulesVersion = businessRulesVersion,
            publicStationCode = publicStationCode,
            cashPaymentEnabled = cashPaymentEnabled,
            codPaymentEnabled = codPaymentEnabled,
            gcashPaymentEnabled = gcashPaymentEnabled,
            gcashAccountName = gcashAccountName,
            gcashAccountNumber = gcashAccountNumber,
            gcashQrImagePath = gcashQrImagePath,
            mayaPaymentEnabled = mayaPaymentEnabled,
            mayaAccountName = mayaAccountName,
            mayaAccountNumber = mayaAccountNumber,
            mayaQrImagePath = mayaQrImagePath,
        )
    }

    @Serializable
    private data class PublicStationOrderingAvailabilityRow(
        @SerialName("station_id") val stationId: String,
        @SerialName("is_ordering_available") val isOrderingAvailable: Boolean,
    )

    @Serializable
    private data class PublicProductRow(
        @SerialName("product_id") val productId: String,
        @SerialName("product_firebase_id") val productFirebaseId: String? = null,
        @SerialName("station_id") val stationId: String,
        @SerialName("station_firebase_id") val stationFirebaseId: String? = null,
        val name: String,
        @SerialName("product_type") val productType: String,
        @SerialName("size_label") val sizeLabel: String,
        val description: String? = null,
        @SerialName("image_path") val imagePath: String? = null,
        @SerialName("price_centavos") val priceCentavos: Long,
        @SerialName("is_available_for_ordering") val isAvailableForOrdering: Boolean,
        @SerialName("promotion_label") val promotionLabel: String? = null,
        @SerialName("promotion_type") val promotionType: String? = null,
        @SerialName("promotion_percent_bps") val promotionPercentBps: Int? = null,
        @SerialName("promotional_price_centavos") val promotionalPriceCentavos: Long? = null,
        @SerialName("promotion_minimum_quantity") val promotionMinimumQuantity: Int? = null,
        @SerialName("promotion_starts_at") val promotionStartsAt: Long? = null,
        @SerialName("promotion_ends_at") val promotionEndsAt: Long? = null,
        @SerialName("promotion_is_active") val promotionIsActive: Boolean = false,
    ) {
        fun toDomain(): PublicProduct {
            val type = runCatching { ProductType.valueOf(productType) }.getOrDefault(ProductType.OTHER)
            val promotionType = promotionType?.let { raw ->
                runCatching { PublicPromotionType.valueOf(raw) }.getOrNull()
            }
            val promotion = if (promotionIsActive && promotionType != null) {
                PublicPromotion(
                    label = promotionLabel?.trim().orEmpty().ifBlank { "Special offer" },
                    type = promotionType,
                    percentBps = promotionPercentBps,
                    promotionalPrice = promotionalPriceCentavos?.let(::Money),
                    minimumQuantity = promotionMinimumQuantity ?: 1,
                    startsAt = promotionStartsAt ?: 0L,
                    endsAt = promotionEndsAt,
                )
            } else null
            return PublicProduct(
                id = productFirebaseId ?: productId,
                name = name,
                type = type,
                sizeLabel = sizeLabel,
                description = description.orEmpty(),
                imagePath = imagePath,
                price = Money(priceCentavos),
                isAvailable = isAvailableForOrdering,
                promotion = promotion,
            )
        }
    }

    private fun String.isUuid(): Boolean = runCatching {
        java.util.UUID.fromString(this)
    }.isSuccess

    private fun Throwable.safeMessage(resource: String): String =
        message?.takeIf { it.isNotBlank() } ?: "Live $resource could not be loaded."

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
    }
}

internal fun confirmedOrderingAvailability(stationId: String, availabilityByStationId: Map<String, Boolean>): Boolean =
    availabilityByStationId[stationId] == true
