package com.aesprt.aquahub_customer.data.order

import com.aesprt.aquahub_customer.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub_customer.domain.*
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Supabase order consumer. It deliberately requires a Supabase Auth session;
 * Firebase ID tokens are not accepted as a substitute for auth.uid().
 */
class SupabaseOrderRepository : OrderRepository {
    private val supabase get() = SupabaseClientProvider.client

    override fun observeOrders(): Flow<OrderSnapshot> = flow {
        while (true) {
            try {
                emit(OrderSnapshot(items = loadOrders()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                emit(OrderSnapshot(errorMessage = error.message ?: "Orders could not be loaded."))
            }
            delay(5_000)
        }
    }

    override suspend fun createOrder(request: CreateOrderRequest): AppResult<CustomerOrder> = runCatching {
        val user = supabase.auth.currentUserOrNull()
            ?: error("Sign in with Supabase before placing an order.")
        val stationId = findStationId(request.stationId)
            ?: error("This station is no longer available.")
        val mappedItems = request.items.map { line ->
            val productId = findProductId(line.product.id)
                ?: error("A selected product is no longer available.")
            productId to line.quantity
        }
        val response = supabase.postgrest.rpc(
            function = "create_order",
            parameters = buildJsonObject {
                put("p_station_id", stationId)
                put("p_client_operation_id", "order:create:${request.idempotencyKey}")
                put("p_source", "CUSTOMER_APP")
                put("p_items", buildJsonArray {
                    mappedItems.forEach { (id, quantity) ->
                        add(buildJsonObject { put("product_id", id); put("quantity", quantity) })
                    }
                })
                put("p_delivery_mode", request.deliveryMode.name)
                put("p_delivery_address", request.deliveryAddress?.addressLine.orEmpty())
                put("p_delivery_place_id", request.deliveryAddress?.placeId)
                put("p_delivery_latitude", request.deliveryAddress?.location?.latitude)
                put("p_delivery_longitude", request.deliveryAddress?.location?.longitude)
                put("p_payment_method", request.paymentMethod.name)
                put("p_customer_note", request.customerNote)
            }
        ).decodeList<CreateOrderResult>().single()
        loadOrder(response.orderId)
    }.fold({ AppResult.Success(it) }, { AppResult.Failure(it.message ?: "Order could not be submitted.") })

    override suspend fun cancelOrder(orderId: String, reason: String): AppResult<Unit> = runCatching {
        supabase.postgrest.rpc(
            function = "cancel_order",
            parameters = buildJsonObject {
                put("p_order_id", orderId)
                put("p_operation_id", "order:cancel:$orderId")
                put("p_reason", reason.take(300))
            }
        )
    }.fold({ AppResult.Success(Unit) }, { AppResult.Failure(it.message ?: "Could not cancel the order.") })

    private suspend fun loadOrders(): List<CustomerOrder> {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return emptyList()
        val rows = supabase.from("orders").select {
            filter { eq("customer_uid", uid) }
            order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
        }.decodeList<SupabaseOrderRow>()
        if (rows.isEmpty()) return emptyList()
        val itemRows = rows.map { it.id }.chunked(100).flatMap { orderIds ->
            supabase.from("order_items").select {
                filter { isIn("order_id", orderIds) }
            }.decodeList<SupabaseOrderItemRow>()
        }
        val itemsByOrder = itemRows.groupBy { it.orderId }
        val cancelledRows = rows.filter { it.status == "CANCELLED" }
        val refunds = loadRefunds(cancelledRows.map { it.id })
        return rows.map { row ->
            row.toCustomerOrder(
                items = itemsByOrder[row.id].orEmpty(),
                refunds = refunds.rows[row.id].orEmpty(),
                refundStatusUnavailable = row.id in refunds.unavailableOrderIds
            )
        }
    }

    private suspend fun loadOrder(orderId: String): CustomerOrder {
        val row = supabase.from("orders").select { filter { eq("id", orderId) } }.decodeList<SupabaseOrderRow>().single()
        val items = supabase.from("order_items").select { filter { eq("order_id", orderId) } }.decodeList<SupabaseOrderItemRow>()
        val refunds = if (row.status == "CANCELLED") loadRefunds(listOf(orderId)) else RefundLoadResult()
        return row.toCustomerOrder(
            items = items,
            refunds = refunds.rows[orderId].orEmpty(),
            refundStatusUnavailable = orderId in refunds.unavailableOrderIds
        )
    }

    private suspend fun loadRefunds(orderIds: List<String>): RefundLoadResult {
        if (orderIds.isEmpty()) return RefundLoadResult()
        val rows = mutableListOf<SupabaseRefundRow>()
        val unavailable = mutableSetOf<String>()
        orderIds.chunked(200).forEach { batch ->
            try {
                rows += supabase.postgrest.rpc(
                    function = "list_order_refunds_for_orders",
                    parameters = buildJsonObject {
                        put("p_order_ids", buildJsonArray { batch.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    }
                ).decodeList<SupabaseRefundRow>()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unavailable += batch
            }
        }
        return RefundLoadResult(
            rows = rows.groupBy(SupabaseRefundRow::orderId).mapValues { (_, refunds) ->
                refunds.map { refund ->
                    CustomerRefundSummary(
                        id = refund.refundId,
                        amount = Money(refund.amountCentavos),
                        status = refund.status,
                        reason = refund.reason,
                        createdAt = refund.createdAtEpoch,
                        completedAt = refund.completedAtEpoch
                    )
                }
            },
            unavailableOrderIds = unavailable
        )
    }

    private data class RefundLoadResult(
        val rows: Map<String, List<CustomerRefundSummary>> = emptyMap(),
        val unavailableOrderIds: Set<String> = emptySet()
    )

    private fun SupabaseOrderRow.toCustomerOrder(
        items: List<SupabaseOrderItemRow>,
        refunds: List<CustomerRefundSummary> = emptyList(),
        refundStatusUnavailable: Boolean = false
    ): CustomerOrder = CustomerOrder(
            id = id, orderNumber = orderNumber, stationId = stationId,
            stationName = customerName.ifBlank { "AquaHub Station" },
            items = items.map { item -> CartLine(PublicProduct(item.productId.orEmpty(), item.productNameSnapshot, ProductType.entries.firstOrNull { it.name == item.productTypeSnapshot } ?: ProductType.OTHER, item.sizeLabelSnapshot, "", null, Money(item.unitPriceCentavos), true), item.quantity) },
            subtotal = Money(subtotalCentavos), deliveryFee = Money(deliveryFeeCentavos),
            discount = Money(discountCentavos), total = Money(totalCentavos),
            status = OrderStatus.entries.firstOrNull { it.name == status } ?: OrderStatus.PENDING,
            deliveryMode = DeliveryMode.entries.firstOrNull { it.name == deliveryMode } ?: DeliveryMode.PICKUP,
            paymentMethod = PaymentMethod.entries.firstOrNull { it.name == paymentMethod } ?: PaymentMethod.CASH,
            deliveryAddress = deliveryAddress, deliveryLocation = deliveryLatitude?.let { lat -> deliveryLongitude?.let { lon -> GeoPoint(lat, lon) } },
            customerNote = customerNote, rejectionReason = rejectionReason,
            requestedAt = requestedAt ?: 0L,
            updatedAt = updatedAtEpoch?.toLongOrNull() ?: 0L,
            cancellationReason = cancellationReason,
            refunds = refunds,
            refundStatusUnavailable = refundStatusUnavailable
        )

    private suspend fun findStationId(legacyId: String): String? = if (legacyId.isUuid()) {
        legacyId
    } else {
        supabase.from("stations").select { filter { eq("firebase_id", legacyId); eq("is_active", true) } }.decodeList<IdRow>().firstOrNull()?.id
    }

    private suspend fun findProductId(legacyId: String): String? = if (legacyId.isUuid()) {
        legacyId
    } else {
        supabase.from("products").select { filter { eq("firebase_id", legacyId); eq("is_active", true) } }.decodeList<IdRow>().firstOrNull()?.id
    }

    private fun String.isUuid(): Boolean = runCatching { java.util.UUID.fromString(this) }.isSuccess

    @Serializable private data class IdRow(val id: String)
    @Serializable private data class CreateOrderResult(@SerialName("order_id") val orderId: String)
    @Serializable private data class SupabaseRefundRow(
        @SerialName("order_id") val orderId: String,
        @SerialName("refund_id") val refundId: String,
        @SerialName("amount_centavos") val amountCentavos: Long,
        val status: String,
        val reason: String,
        @SerialName("created_at") val createdAtText: String,
        @SerialName("completed_at") val completedAtText: String? = null
    ) {
        val createdAtEpoch: Long get() = java.time.Instant.parse(createdAtText).toEpochMilli()
        val completedAtEpoch: Long? get() = completedAtText?.let { java.time.Instant.parse(it).toEpochMilli() }
    }
    @Serializable private data class SupabaseOrderRow(
        val id: String, @SerialName("station_id") val stationId: String, @SerialName("order_number") val orderNumber: String,
        @SerialName("customer_name") val customerName: String, @SerialName("delivery_address") val deliveryAddress: String,
        @SerialName("delivery_mode") val deliveryMode: String, @SerialName("subtotal_centavos") val subtotalCentavos: Long,
        @SerialName("delivery_fee_centavos") val deliveryFeeCentavos: Long, @SerialName("discount_centavos") val discountCentavos: Long,
        @SerialName("total_centavos") val totalCentavos: Long, @SerialName("payment_method") val paymentMethod: String,
        val status: String, @SerialName("customer_note") val customerNote: String? = null,
        @SerialName("rejection_reason") val rejectionReason: String? = null, @SerialName("delivery_latitude") val deliveryLatitude: Double? = null,
        @SerialName("cancellation_reason") val cancellationReason: String? = null,
        @SerialName("delivery_longitude") val deliveryLongitude: Double? = null, @SerialName("requested_at") val requestedAt: Long? = null,
        @SerialName("updated_at") val updatedAtEpoch: String? = null
    )
    @Serializable private data class SupabaseOrderItemRow(@SerialName("order_id") val orderId: String, @SerialName("product_id") val productId: String? = null, @SerialName("product_name_snapshot") val productNameSnapshot: String, @SerialName("product_type_snapshot") val productTypeSnapshot: String, @SerialName("size_label_snapshot") val sizeLabelSnapshot: String, @SerialName("unit_price_centavos") val unitPriceCentavos: Long, val quantity: Int, @SerialName("container_source") val containerSource: String? = null)
}
