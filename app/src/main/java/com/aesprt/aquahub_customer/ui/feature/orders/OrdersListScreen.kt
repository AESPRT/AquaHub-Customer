package com.aesprt.aquahub_customer.ui.feature.orders

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub_customer.domain.*
import com.aesprt.aquahub_customer.ui.components.AquaCustomerTopBar
import com.aesprt.aquahub_customer.ui.components.AquaHubGlassCard
import com.aesprt.aquahub_customer.ui.components.EmptyStateView
import com.aesprt.aquahub_customer.ui.components.OrderStatusChip
import com.aesprt.aquahub_customer.ui.theme.*
import java.text.DateFormat
import java.util.Date

@Composable
fun OrdersListScreen(
    modifier: Modifier = Modifier,
    orders: List<CustomerOrder>,
    ordersError: String? = null,
    ordersFromCache: Boolean = false,
    onRetry: () -> Unit = {},
    onOrderClick: (String) -> Unit,
    onReorder: (CustomerOrder) -> Unit,
    onBrowseStations: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Active Orders", "Completed")

    val activeOrders = remember(orders) { orders.filter { !it.status.isTerminal } }
    val pastOrders = remember(orders) { orders.filter { it.status.isTerminal } }
    val displayedOrders = if (selectedTab == 0) activeOrders else pastOrders

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        containerColor = Color.Transparent,
        topBar = {
            AquaCustomerTopBar(
                title = "My Orders",
                subtitle = "Track and manage your water deliveries"
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (ordersFromCache && orders.isNotEmpty()) {
                Text(
                    text = "Showing the last available order history",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            ordersError?.let { error ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    shape = AquaHubShapes.cardSecondary,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRetry) { Text("Try again") }
                    }
                }
            }

            // Tab Selector
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tabs.forEachIndexed { index, label ->
                        val isSelected = selectedTab == index
                        val count = if (index == 0) activeOrders.size else pastOrders.size
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedTab = index },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                            shadowElevation = if (isSelected) 2.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (count > 0) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = count.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (displayedOrders.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyStateView(
                        title = if (selectedTab == 0) "No active orders" else "No previous orders",
                        description = if (selectedTab == 0) "When you place a water refill or container order, track its journey right here."
                        else "Completed and fulfilled water orders will be archived here for easy reordering.",
                        icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                        actionLabel = if (selectedTab == 0) "Order Water Now" else null,
                        onAction = onBrowseStations
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(displayedOrders, key = { it.id }) { order ->
                        OrderHistoryCard(
                            order = order,
                            onClick = { onOrderClick(order.id) },
                            onReorder = { onReorder(order) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderHistoryCard(
    order: CustomerOrder,
    onClick: () -> Unit,
    onReorder: () -> Unit,
    modifier: Modifier = Modifier
) {
    AquaHubGlassCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        shape = AquaHubShapes.cardPrimary
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${order.orderNumber}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OrderStatusChip(status = order.status)
            }

            Text(
                text = order.stationName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = order.items.joinToString(", ") { "${it.quantity}× ${it.product.name} (${it.product.sizeLabel})" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 2.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(order.requestedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = order.total.format(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (order.status.isTerminal) {
                    FilledTonalButton(
                        onClick = onReorder,
                        shape = AquaHubShapes.chip,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Replay,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Reorder", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Track",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Light Mode", showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OrdersListScreenPreview() {
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
    val sampleOrder1 = CustomerOrder(
        id = "ord-1",
        orderNumber = "ORD-1001",
        stationId = "st-1",
        stationName = "AquaPure Station",
        items = listOf(CartLine(sampleProduct, 2)),
        subtotal = Money.fromPesos(70),
        deliveryFee = Money.fromPesos(25),
        discount = Money.ZERO,
        total = Money.fromPesos(95),
        status = OrderStatus.OUT_FOR_DELIVERY,
        deliveryMode = DeliveryMode.DELIVERY,
        deliveryAddress = "123 Main St, Quezon City",
        customerNote = "Please leave at gate",
        rejectionReason = null,
        requestedAt = System.currentTimeMillis() - 3600000,
        updatedAt = System.currentTimeMillis()
    )
    val sampleOrder2 = CustomerOrder(
        id = "ord-2",
        orderNumber = "ORD-0992",
        stationId = "st-1",
        stationName = "AquaPure Station",
        items = listOf(CartLine(sampleProduct, 1)),
        subtotal = Money.fromPesos(35),
        deliveryFee = Money.fromPesos(25),
        discount = Money.ZERO,
        total = Money.fromPesos(60),
        status = OrderStatus.COMPLETED,
        deliveryMode = DeliveryMode.DELIVERY,
        deliveryAddress = "123 Main St, Quezon City",
        customerNote = null,
        rejectionReason = null,
        requestedAt = System.currentTimeMillis() - 86400000,
        updatedAt = System.currentTimeMillis() - 82800000
    )

    AquaHubCustomerTheme(dynamicColor = false) {
        OrdersListScreen(
            orders = listOf(sampleOrder1, sampleOrder2),
            onOrderClick = {},
            onReorder = {},
            onBrowseStations = {}
        )
    }
}
