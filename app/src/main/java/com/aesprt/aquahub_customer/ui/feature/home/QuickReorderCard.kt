package com.aesprt.aquahub_customer.ui.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub_customer.domain.CustomerOrder
import com.aesprt.aquahub_customer.ui.components.AquaHubGlassCard
import com.aesprt.aquahub_customer.ui.theme.*

@Composable
fun QuickReorderCard(
    order: CustomerOrder,
    onReorder: (CustomerOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    AquaHubGlassCard(
        modifier = modifier.fillMaxWidth(),
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "ORDER AGAIN",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.2.sp
                    )
                }

                Text(
                    text = order.total.format(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WaterDrop,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = order.items.joinToString(", ") { "${it.quantity}× ${it.product.name}" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = order.stationName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { onReorder(order) },
                    shape = AquaHubShapes.chip,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Replay,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Reorder", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun QuickReorderCardPreview() {
    val sampleOrder = CustomerOrder(
        id = "ord-1",
        orderNumber = "AH-17305337",
        stationId = "st-1",
        stationName = "aquaman Refilling Station",
        items = listOf(
            com.aesprt.aquahub_customer.domain.CartLine(
                product = com.aesprt.aquahub_customer.domain.PublicProduct(
                    id = "p-1",
                    name = "5 Gallon Round (Refill)",
                    type = com.aesprt.aquahub_customer.domain.ProductType.REFILL,
                    price = com.aesprt.aquahub_customer.domain.Money.fromPesos(50),
                    description = "Purified drinking water",
                    sizeLabel = "20 Litters",
                    imagePath = null,
                    isAvailable = true
                ),
                quantity = 2
            )
        ),
        subtotal = com.aesprt.aquahub_customer.domain.Money.fromPesos(100),
        deliveryFee = com.aesprt.aquahub_customer.domain.Money.fromPesos(25),
        discount = com.aesprt.aquahub_customer.domain.Money.Zero,
        total = com.aesprt.aquahub_customer.domain.Money.fromPesos(125),
        status = com.aesprt.aquahub_customer.domain.OrderStatus.COMPLETED,
        deliveryMode = com.aesprt.aquahub_customer.domain.DeliveryMode.DELIVERY,
        deliveryAddress = "123 Main St, Quezon City",
        customerNote = null,
        rejectionReason = null,
        requestedAt = System.currentTimeMillis() - 86400000,
        updatedAt = System.currentTimeMillis() - 82800000
    )
    AquaHubCustomerTheme(dynamicColor = false) {
        Box(modifier = Modifier.padding(16.dp)) {
            QuickReorderCard(
                order = sampleOrder,
                onReorder = {}
            )
        }
    }
}
