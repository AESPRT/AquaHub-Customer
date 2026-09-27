package com.aesprt.aquahub_customer.data.catalog

import android.content.Context
import com.aesprt.aquahub_customer.data.FIRESTORE_DATABASE
import com.aesprt.aquahub_customer.domain.CatalogRepository
import com.aesprt.aquahub_customer.domain.CatalogSnapshot
import com.aesprt.aquahub_customer.domain.AppResult
import com.aesprt.aquahub_customer.domain.GeoPoint
import com.aesprt.aquahub_customer.domain.Money
import com.aesprt.aquahub_customer.domain.ProductType
import com.aesprt.aquahub_customer.domain.PublicProduct
import com.aesprt.aquahub_customer.domain.PublicPromotion
import com.aesprt.aquahub_customer.domain.PublicPromotionType
import com.aesprt.aquahub_customer.domain.PublicStation
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirestoreCatalogRepository(
    context: Context,
) : CatalogRepository {
    private val configured = FirebaseApp.getApps(context).isNotEmpty()
    private val db: FirebaseFirestore
        get() = FirebaseFirestore.getInstance(FirebaseApp.getInstance(), FIRESTORE_DATABASE)

    override fun observeStation(
        businessId: String,
        stationId: String,
    ): Flow<CatalogSnapshot<PublicStation>> {
        if (!configured) {
            return flowOf(CatalogSnapshot(emptyList(), false, "Firebase is not configured."))
        }
        return callbackFlow {
            val registration = db.collection("businesses").document(businessId)
                .collection("publicStations").document(stationId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(CatalogSnapshot(emptyList(), false, error.customerMessage("station")))
                        return@addSnapshotListener
                    }
                    val fromCache = snapshot?.metadata?.isFromCache == true
                    val station = snapshot
                        ?.takeIf { it.exists() && it.getBoolean("isActive") == true }
                        ?.toPublicStation(fromCache)
                    trySend(CatalogSnapshot(listOfNotNull(station), fromCache))
                }
            awaitClose { registration.remove() }
        }
    }

    override fun observeProducts(businessId: String, stationId: String): Flow<CatalogSnapshot<PublicProduct>> {
        if (!configured) {
            return flowOf(CatalogSnapshot(emptyList(), false, "Firebase is not configured."))
        }
        return callbackFlow {
            val registration = db.collection("businesses").document(businessId)
                .collection("publicStations").document(stationId)
                .collection("products")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(CatalogSnapshot(emptyList(), false, error.customerMessage("products")))
                        return@addSnapshotListener
                    }
                    val fromCache = snapshot?.metadata?.isFromCache == true
                    val products = snapshot?.documents.orEmpty()
                        .filter { it.getBoolean("isAvailableForOrdering") != false }
                        .mapNotNull(DocumentSnapshot::toPublicProduct)
                    trySend(CatalogSnapshot(products, fromCache))
                }
            awaitClose { registration.remove() }
        }
    }

    override suspend fun resolveStation(publicStationCode: String): AppResult<PublicStation> {
        if (!configured) return AppResult.Failure("Firebase is not configured.", recoverable = false)
        val code = publicStationCode.trim()
        if (!Regex("[A-Za-z0-9_-]{6,64}").matches(code)) {
            return AppResult.Failure("This ordering link is invalid.", recoverable = false)
        }
        return runCatching {
            val snapshot = db.collectionGroup("publicStations")
                .whereEqualTo("publicStationCode", code)
                .limit(1)
                .get()
                .await()
            val station = snapshot.documents.firstOrNull()?.toPublicStation(false)
                ?: error("This station ordering link is no longer available.")
            AppResult.Success(station)
        }.getOrElse { error ->
            AppResult.Failure(error.stationLinkMessage())
        }
    }
}

private fun DocumentSnapshot.toPublicStation(fromCache: Boolean): PublicStation? = runCatching {
    val rawLocation = get("location")
    val firestorePoint = rawLocation as? com.google.firebase.firestore.GeoPoint
    val locationMap = rawLocation as? Map<*, *>
    val latitude = number("latitude") ?: number("lat") ?: (locationMap?.get("latitude") as? Number)?.toDouble() ?: firestorePoint?.latitude
    val longitude = number("longitude") ?: number("lng") ?: (locationMap?.get("longitude") as? Number)?.toDouble() ?: firestorePoint?.longitude
    val addressText = (get("address") as? String) ?: getString("addressLine") ?: listOfNotNull(getString("barangay"), getString("city"), getString("province")).joinToString(", ")
    // isOpen is the owner's explicit station-availability switch. Do not let
    // stale acceptance flags make a station look open after the owner closes it.
    val isOwnerOpen = getBoolean("isOpen") == true
    val acceptsOrders = getBoolean("isAcceptingOnlineOrders") == true ||
        getBoolean("isAcceptingOrders") == true ||
        (get("isAcceptingOnlineOrders") == null && get("isAcceptingOrders") == null && isOwnerOpen)
    PublicStation(
        id = getString("stationId") ?: getString("id") ?: id,
        businessId = getString("businessId") ?: reference.parent.parent?.id.orEmpty(),
        name = getString("name") ?: getString("stationName").orEmpty(),
        phone = getString("phone").orEmpty(),
        address = addressText,
        location = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null,
        isAcceptingOrders = isOwnerOpen && acceptsOrders,
        openingTime = getString("openingTime"),
        closingTime = getString("closingTime"),
        deliveryRadiusKm = number("deliveryRadiusKm") ?: 0.0,
        deliveryFee = Money((get("deliveryFeeCentavos") as? Number)?.toLong() ?: 0L),
        estimatedPreparationMinutes =
            (get("estimatedPreparationMinutes") as? Number)?.toInt() ?: 30,
        isOpen = isOwnerOpen,
        manualOpenOverride = getBoolean("manualOpenOverride") == true,
        logoPath = getString("logoPath"),
        isFromCache = fromCache,
        averageRating = (number("averageRating") ?: number("rating"))?.toFloat(),
        ratingCount = (number("ratingCount") ?: number("totalReviews"))?.toInt() ?: 0,
        businessRulesText = getString("businessRulesText")?.trim()?.takeIf { it.isNotBlank() },
        businessRulesVersion = (get("businessRulesVersion") as? Number)?.toLong() ?: 0L,
        publicStationCode = getString("publicStationCode")?.trim()?.takeIf { it.isNotBlank() },
        cashPaymentEnabled = getBoolean("cashPaymentEnabled") != false,
        codPaymentEnabled = getBoolean("codPaymentEnabled") != false,
        gcashPaymentEnabled = getBoolean("gcashPaymentEnabled") == true,
        gcashAccountName = getString("gcashAccountName")?.trim()?.takeIf { it.isNotBlank() },
        gcashAccountNumber = getString("gcashAccountNumber")?.trim()?.takeIf { it.isNotBlank() },
        gcashQrImagePath = getString("gcashQrImagePath")?.trim()?.takeIf { it.isNotBlank() },
        mayaPaymentEnabled = getBoolean("mayaPaymentEnabled") == true,
        mayaAccountName = getString("mayaAccountName")?.trim()?.takeIf { it.isNotBlank() },
        mayaAccountNumber = getString("mayaAccountNumber")?.trim()?.takeIf { it.isNotBlank() },
        mayaQrImagePath = getString("mayaQrImagePath")?.trim()?.takeIf { it.isNotBlank() },
    )
}.getOrNull()

private fun DocumentSnapshot.toPublicProduct(): PublicProduct? = runCatching {
    val promotionType = getString("promotionType")?.let { raw ->
        runCatching { PublicPromotionType.valueOf(raw) }.getOrNull()
    }
    val promotion = if (getBoolean("promotionIsActive") == true && promotionType != null) {
        PublicPromotion(
            label = getString("promotionLabel")?.trim().orEmpty().ifBlank { "Special offer" },
            type = promotionType,
            percentBps = (get("promotionPercentBps") as? Number)?.toInt(),
            promotionalPrice = (get("promotionalPriceCentavos") as? Number)?.toLong()?.let(::Money),
            minimumQuantity = (get("promotionMinimumQuantity") as? Number)?.toInt() ?: 1,
            startsAt = (get("promotionStartsAt") as? Number)?.toLong() ?: 0L,
            endsAt = (get("promotionEndsAt") as? Number)?.toLong(),
        )
    } else null
    PublicProduct(
        id = getString("productId") ?: id,
        name = getString("name").orEmpty(),
        type = runCatching {
            ProductType.valueOf(getString("productType") ?: "OTHER")
        }.getOrDefault(ProductType.OTHER),
        sizeLabel = getString("sizeLabel").orEmpty(),
        description = getString("description").orEmpty(),
        imagePath = getString("imagePath"),
        price = Money((get("priceCentavos") as? Number)?.toLong() ?: 0L),
        isAvailable = getBoolean("isAvailableForOrdering") ?: getBoolean("isAvailable") ?: getBoolean("isActive") ?: true,
        promotion = promotion,
    )
}.getOrNull()

private fun DocumentSnapshot.number(field: String): Double? =
    (get(field) as? Number)?.toDouble()

private fun FirebaseFirestoreException.customerMessage(resource: String): String = when (code) {
    FirebaseFirestoreException.Code.PERMISSION_DENIED ->
        "Live $resource access is not enabled. Ask the administrator to deploy the customer Firestore rules."
    else -> message ?: "Live $resource could not be loaded."
}

private fun Throwable.stationLinkMessage(): String = when ((this as? FirebaseFirestoreException)?.code) {
    FirebaseFirestoreException.Code.FAILED_PRECONDITION ->
        "Station linking is being prepared. Please try again shortly."
    FirebaseFirestoreException.Code.UNAVAILABLE ->
        "AquaHub could not be reached. Check your connection and try again."
    FirebaseFirestoreException.Code.PERMISSION_DENIED ->
        "This station link cannot be opened with your account."
    else -> message
        ?.takeIf { it == "This station ordering link is no longer available." }
        ?: "The station QR code could not be opened. Please try again."
}
