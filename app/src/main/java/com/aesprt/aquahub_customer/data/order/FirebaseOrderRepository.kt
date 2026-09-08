package com.aesprt.aquahub_customer.data.order

import android.content.Context
import com.aesprt.aquahub_customer.data.FIRESTORE_DATABASE
import com.aesprt.aquahub_customer.domain.AppResult
import com.aesprt.aquahub_customer.domain.CartLine
import com.aesprt.aquahub_customer.domain.CreateOrderRequest
import com.aesprt.aquahub_customer.domain.CustomerOrder
import com.aesprt.aquahub_customer.domain.DeliveryMode
import com.aesprt.aquahub_customer.domain.GeoPoint
import com.aesprt.aquahub_customer.domain.Money
import com.aesprt.aquahub_customer.domain.OrderRepository
import com.aesprt.aquahub_customer.domain.OrderStatus
import com.aesprt.aquahub_customer.domain.ProductType
import com.aesprt.aquahub_customer.domain.PublicProduct
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirebaseOrderRepository(
    context: Context,
) : OrderRepository {
    private val configured = FirebaseApp.getApps(context).isNotEmpty()
    private val db: FirebaseFirestore
        get() = FirebaseFirestore.getInstance(FirebaseApp.getInstance(), FIRESTORE_DATABASE)
    private val functions: FirebaseFunctions
        get() = FirebaseFunctions.getInstance("asia-southeast1")

    override fun observeOrders(): Flow<List<CustomerOrder>> {
        if (!configured) return flowOf(emptyList())
        return callbackFlow {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid == null) {
                trySend(emptyList())
                close()
                return@callbackFlow
            }
            val registration = db.collection("users").document(uid).collection("orders")
                .orderBy("requestedAt", Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, _ ->
                    trySend(
                        snapshot?.documents.orEmpty()
                            .mapNotNull { it.data?.toOrder(it.id) },
                    )
                }
            awaitClose { registration.remove() }
        }
    }

    override suspend fun createOrder(request: CreateOrderRequest): AppResult<CustomerOrder> {
        request.validate().firstOrNull()?.let { return AppResult.Failure(it) }
        if (!configured) return AppResult.Failure("Firebase is not configured.")
        val signedInUser = FirebaseAuth.getInstance().currentUser
            ?: return AppResult.Failure("Your session expired. Sign in again before ordering.")
        val payload = mapOf(
            "businessId" to request.businessId,
            "stationId" to request.stationId,
            "items" to request.items.map {
                mapOf("productId" to it.product.id, "quantity" to it.quantity)
            },
            "deliveryAddress" to request.deliveryAddress?.let {
                mapOf(
                    "label" to it.label,
                    "addressLine" to it.addressLine,
                    "placeId" to it.placeId,
                    "latitude" to it.location?.latitude,
                    "longitude" to it.location?.longitude,
                )
            },
            "deliveryMode" to request.deliveryMode.name,
            "paymentMethod" to request.paymentMethod.name,
            "customerNote" to request.customerNote,
            "acceptedBusinessRulesVersion" to request.acceptedBusinessRulesVersion,
            "idempotencyKey" to request.idempotencyKey,
        )
        return runCatching {
            // Ensure Firebase Auth has minted a token before invoking the callable. If a
            // just-restored session sends a stale token, refresh once; the idempotency key
            // makes the retry safe even if the first request reached the backend.
            signedInUser.getIdToken(false).await()
            val response = try {
                callCreateOrder(payload)
            } catch (error: FirebaseFunctionsException) {
                if (error.code != FirebaseFunctionsException.Code.UNAUTHENTICATED) throw error
                signedInUser.getIdToken(true).await()
                callCreateOrder(payload)
            }
            val orderId = response["orderId"] as String
            val document = db.collection("users").document(signedInUser.uid)
                .collection("orders").document(orderId).get().await()
            document.data?.toOrder(orderId)
                ?: error("Order was created but could not be loaded.")
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = {
                AppResult.Failure(it.message ?: "Order could not be submitted. Your cart is safe.")
            },
        )
    }

    override suspend fun cancelOrder(orderId: String, reason: String): AppResult<Unit> {
        if (!configured) return AppResult.Failure("Firebase is not configured.")
        val signedInUser = FirebaseAuth.getInstance().currentUser
            ?: return AppResult.Failure("Your session expired. Sign in again.")
        return runCatching {
            signedInUser.getIdToken(false).await()
            functions.getHttpsCallable("cancelCustomerOrder")
                .call(mapOf("orderId" to orderId, "reason" to reason.take(300)))
                .await()
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(it.message ?: "Could not cancel the order.") },
        )
    }

    private suspend fun callCreateOrder(payload: Map<String, Any?>): Map<*, *> =
        functions.getHttpsCallable(
            "createCustomerOrder",
            HttpsCallableOptions.Builder()
                .setLimitedUseAppCheckTokens(true)
                .build(),
        )
            .call(payload)
            .await()
            .data as Map<*, *>
}

private fun Map<String, Any?>.toOrder(id: String): CustomerOrder? = runCatching {
    val rawItems = this["items"] as? List<Map<String, Any?>> ?: emptyList()
    CustomerOrder(
        id = id,
        orderNumber = this["orderNumber"] as? String ?: id.take(8).uppercase(),
        stationId = this["stationId"] as String,
        stationName = this["stationName"] as? String ?: "AquaHub Station",
        items = rawItems.map { item ->
            val unitPrice = (item["unitPriceCentavos"] as Number).toLong()
            CartLine(
                PublicProduct(
                    id = item["productId"] as? String ?: "snapshot",
                    name = item["productNameSnapshot"] as String,
                    type = runCatching {
                        ProductType.valueOf(item["productTypeSnapshot"] as String)
                    }.getOrDefault(ProductType.OTHER),
                    sizeLabel = item["sizeLabelSnapshot"] as? String ?: "",
                    description = "",
                    imagePath = null,
                    price = Money(unitPrice),
                    isAvailable = true,
                ),
                (item["quantity"] as Number).toInt(),
            )
        },
        subtotal = Money((this["subtotalCentavos"] as Number).toLong()),
        deliveryFee = Money((this["deliveryFeeCentavos"] as Number).toLong()),
        discount = Money((this["discountCentavos"] as Number).toLong()),
        total = Money((this["totalCentavos"] as Number).toLong()),
        status = OrderStatus.valueOf(this["status"] as String),
        deliveryMode = runCatching {
            DeliveryMode.valueOf(this["deliveryMode"] as? String ?: "DELIVERY")
        }.getOrDefault(DeliveryMode.DELIVERY),
        deliveryAddress = this["deliveryAddress"] as? String ?: "Station pickup",
        deliveryLocation = ((this["deliveryLatitude"] as? Number)?.toDouble())?.let { latitude ->
            (this["deliveryLongitude"] as? Number)?.toDouble()?.let { longitude ->
                GeoPoint(latitude, longitude)
            }
        },
        customerNote = this["customerNote"] as? String,
        rejectionReason = this["rejectionReason"] as? String,
        requestedAt = (this["requestedAt"] as? Number)?.toLong() ?: 0L,
        updatedAt = (this["clientUpdatedAt"] as? Number)?.toLong() ?: 0L,
    )
}.getOrNull()
