package com.aesprt.aquahub_customer.ui.feature.orders

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub_customer.domain.*
import com.aesprt.aquahub_customer.ui.CustomerViewModel
import com.aesprt.aquahub_customer.ui.components.*
import com.aesprt.aquahub_customer.ui.theme.*
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderTrackingScreen(
    order: CustomerOrder,
    viewModel: CustomerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    OrderTrackingContent(
        order = order,
        onBack = onBack,
        onCancelOrder = { viewModel.cancelOrder(order.id) },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderTrackingContent(
    order: CustomerOrder,
    onBack: () -> Unit,
    onCancelOrder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showCancelDialog by remember { mutableStateOf(false) }

    val progressFraction = remember(order.status) {
        when (order.status) {
            OrderStatus.PENDING -> 0.15f
            OrderStatus.ACCEPTED -> 0.30f
            OrderStatus.PREPARING -> 0.50f
            OrderStatus.READY_FOR_PICKUP -> 0.75f
            OrderStatus.RIDER_ASSIGNED -> 0.70f
            OrderStatus.OUT_FOR_DELIVERY -> 0.88f
            OrderStatus.DELIVERED, OrderStatus.COMPLETED -> 1.0f
            OrderStatus.REJECTED, OrderStatus.CANCELLED -> 0.0f
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "orderProgress"
    )

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        containerColor = Color.Transparent,
        topBar = {
            AquaCustomerTopBar(
                title = "Order #${order.orderNumber}",
                subtitle = order.stationName,
                onBack = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Live Status Hero Banner
            item {
                Surface(
                    shape = AquaHubShapes.cardPrimary,
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(aquaHeroGradient())
                            .padding(22.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.onPrimary)
                                    )
                                    Text(
                                        text = "LIVE STATUS",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                                        letterSpacing = 1.2.sp
                                    )
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
                                    shape = CircleShape
                                ) {
                                    Text(
                                        text = order.status.label,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Text(
                                text = when (order.status) {
                                    OrderStatus.PENDING -> "Waiting for station confirmation"
                                    OrderStatus.ACCEPTED -> "Station accepted your order"
                                    OrderStatus.PREPARING -> "Purifying and filling your containers"
                                    OrderStatus.READY_FOR_PICKUP -> "Your order is ready for pickup"
                                    OrderStatus.RIDER_ASSIGNED -> "Delivery partner assigned"
                                    OrderStatus.OUT_FOR_DELIVERY -> "Rider is heading to your address"
                                    OrderStatus.DELIVERED -> "Water successfully delivered!"
                                    OrderStatus.COMPLETED -> "Order completed"
                                    OrderStatus.REJECTED -> "Station could not fulfill order"
                                    OrderStatus.CANCELLED -> "Order was cancelled"
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                lineHeight = 28.sp
                            )

                            if (!order.status.isTerminal) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    LinearProgressIndicator(
                                        progress = { animatedProgress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(CircleShape),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f)
                                    )
                                    Text(
                                        text = "Updated ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(order.updatedAt))}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Rider Card (shown only if rider is assigned or out for delivery)
            if (order.status == OrderStatus.RIDER_ASSIGNED || order.status == OrderStatus.OUT_FOR_DELIVERY) {
                item {
                    AquaHubGlassCard(shape = AquaHubShapes.cardSecondary) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.TwoWheeler,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "AquaHub Delivery Partner",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Assigned Delivery Rider",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "On motorcycle with insulated container carrier",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Order Timeline
            item {
                Text(
                    text = "Delivery Journey",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            item {
                AquaHubGlassCard(shape = AquaHubShapes.cardPrimary) {
                    OrderTimeline(
                        status = order.status,
                        deliveryMode = order.deliveryMode
                    )
                }
            }

            // Fulfilment & Address Details
            item {
                AquaHubGlassCard(shape = AquaHubShapes.cardSecondary) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = if (order.deliveryMode == DeliveryMode.DELIVERY) Icons.Outlined.LocationOn else Icons.Outlined.Storefront,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (order.deliveryMode == DeliveryMode.DELIVERY) "Delivery Address" else "Pickup Station",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = order.deliveryAddress.ifBlank { order.stationName },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (!order.customerNote.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Instructions: \"${order.customerNote}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            }
                        }
                    }
                }
            }

            item {
                AquaHubGlassCard(shape = AquaHubShapes.cardSecondary) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Payments, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Payment method", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(order.paymentMethod.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Order Items & Financial Summary
            item {
                OrderSummaryCard(
                    subtotal = order.subtotal,
                    deliveryFee = order.deliveryFee,
                    discount = order.discount,
                    total = order.total
                )
            }

            // Cancellation CTA (if order is still in PENDING or ACCEPTED state)
            if (order.status == OrderStatus.PENDING || order.status == OrderStatus.ACCEPTED) {
                item {
                    AquaSecondaryButton(
                        text = "Cancel Order",
                        onClick = { showCancelDialog = true },
                        icon = Icons.Outlined.Cancel,
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        contentColor = MaterialTheme.colorScheme.error,
                        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        // Cancel Confirmation Dialog
        if (showCancelDialog) {
            AlertDialog(
                onDismissRequest = { showCancelDialog = false },
                title = {
                    Text(
                        text = "Cancel this order?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "The station may have already started preparing your water. Are you sure you want to cancel?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onCancelOrder()
                            showCancelDialog = false
                        }
                    ) {
                        Text(
                            text = "Yes, Cancel",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCancelDialog = false }) {
                        Text("Keep Order")
                    }
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OrderTrackingScreenPreview() {
    val sampleProduct = PublicProduct(
        id = "prod-1",
        name = "Mineral Water 5 Gallon",
        type = ProductType.REFILL,
        sizeLabel = "5 Gallon Round",
        description = "Clean purified drinking water",
        imagePath = null,
        price = Money.fromPesos(35),
        isAvailable = true
    )
    val sampleOrder = CustomerOrder(
        id = "ord-1",
        orderNumber = "ORD-2024",
        stationId = "st-1",
        stationName = "AquaPure Station",
        items = listOf(CartLine(sampleProduct, 3)),
        subtotal = Money.fromPesos(105),
        deliveryFee = Money.fromPesos(25),
        discount = Money.ZERO,
        total = Money.fromPesos(130),
        status = OrderStatus.OUT_FOR_DELIVERY,
        deliveryMode = DeliveryMode.DELIVERY,
        deliveryAddress = "123 Main St, Quezon City",
        customerNote = "Gate code: #1234",
        rejectionReason = null,
        requestedAt = System.currentTimeMillis() - 1800000,
        updatedAt = System.currentTimeMillis()
    )

    AquaHubCustomerTheme(dynamicColor = false) {
        OrderTrackingContent(
            order = sampleOrder,
            onBack = {},
            onCancelOrder = {}
        )
    }
}
