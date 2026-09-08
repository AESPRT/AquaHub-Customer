package com.aesprt.aquahub_customer.data.catalog

import android.content.Context
import com.aesprt.aquahub_customer.data.FIRESTORE_DATABASE
import com.aesprt.aquahub_customer.domain.CatalogRepository
import com.aesprt.aquahub_customer.domain.CatalogSnapshot
import com.aesprt.aquahub_customer.domain.GeoPoint
import com.aesprt.aquahub_customer.domain.Money
import com.aesprt.aquahub_customer.domain.ProductType
import com.aesprt.aquahub_customer.domain.PublicProduct
import com.aesprt.aquahub_customer.domain.PublicStation
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.callbackFlow

class FirestoreCatalogRepository(
    context: Context,
) : CatalogRepository {
    private val configured = FirebaseApp.getApps(context).isNotEmpty()
    private val db: FirebaseFirestore
        get() = FirebaseFirestore.getInstance(FirebaseApp.getInstance(), FIRESTORE_DATABASE)

    override fun observeStations(): Flow<CatalogSnapshot<PublicStation>> {
        if (!configured) {
            return flowOf(CatalogSnapshot(emptyList(), false, "Firebase is not configured."))
        }
        return callbackFlow {
            val registration = db.collectionGroup("stations")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(CatalogSnapshot(emptyList(), false, error.customerMessage("stations")))
                        return@addSnapshotListener
                    }
                    val fromCache = snapshot?.metadata?.isFromCache == true
                    val stations = snapshot?.documents.orEmpty()
                        .filter { it.getBoolean("isActive") != false }
                        .mapNotNull { it.toPublicStation(fromCache) }
                    trySend(CatalogSnapshot(stations, fromCache))
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
                .collection("stations").document(stationId)
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
}

private fun DocumentSnapshot.toPublicStation(fromCache: Boolean): PublicStation? = runCatching {
    val rawLocation = get("location")
    val firestorePoint = rawLocation as? com.google.firebase.firestore.GeoPoint
    val locationMap = rawLocation as? Map<*, *>
    val latitude = number("latitude") ?: number("lat") ?: (locationMap?.get("latitude") as? Number)?.toDouble() ?: firestorePoint?.latitude
    val longitude = number("longitude") ?: number("lng") ?: (locationMap?.get("longitude") as? Number)?.toDouble() ?: firestorePoint?.longitude
    val addressText = (get("address") as? String) ?: getString("addressLine") ?: listOfNotNull(getString("barangay"), getString("city"), getString("province")).joinToString(", ")
    PublicStation(
        id = getString("stationId") ?: getString("id") ?: id,
        businessId = getString("businessId") ?: reference.parent.parent?.id.orEmpty(),
        name = getString("name") ?: getString("stationName").orEmpty(),
        phone = getString("phone").orEmpty(),
        address = addressText,
        location = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null,
        isAcceptingOrders = getBoolean("isAcceptingOnlineOrders") == true || getBoolean("isAcceptingOrders") == true || getBoolean("isOpen") == true,
        openingTime = getString("openingTime"),
        closingTime = getString("closingTime"),
        deliveryRadiusKm = number("deliveryRadiusKm") ?: 0.0,
        deliveryFee = Money((get("deliveryFeeCentavos") as? Number)?.toLong() ?: 0L),
        estimatedPreparationMinutes =
            (get("estimatedPreparationMinutes") as? Number)?.toInt() ?: 30,
        logoPath = getString("logoPath"),
        isFromCache = fromCache,
        averageRating = (number("averageRating") ?: number("rating"))?.toFloat(),
        ratingCount = (number("ratingCount") ?: number("totalReviews"))?.toInt() ?: 0,
        businessRulesText = getString("businessRulesText")?.trim()?.takeIf { it.isNotBlank() },
        businessRulesVersion = (get("businessRulesVersion") as? Number)?.toLong() ?: 0L,
    )
}.getOrNull()

private fun DocumentSnapshot.toPublicProduct(): PublicProduct? = runCatching {
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
    )
}.getOrNull()

private fun DocumentSnapshot.number(field: String): Double? =
    (get(field) as? Number)?.toDouble()

private fun FirebaseFirestoreException.customerMessage(resource: String): String = when (code) {
    FirebaseFirestoreException.Code.PERMISSION_DENIED ->
        "Live $resource access is not enabled. Ask the administrator to deploy the customer Firestore rules."
    else -> message ?: "Live $resource could not be loaded."
}
