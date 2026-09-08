package com.aesprt.aquahub_customer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub_customer.domain.DeliveryMode
import com.aesprt.aquahub_customer.domain.OrderStatus
import com.aesprt.aquahub_customer.ui.theme.*

@Composable
fun OrderTimeline(
    status: OrderStatus,
    deliveryMode: DeliveryMode,
    modifier: Modifier = Modifier
) {
    val steps = if (deliveryMode == DeliveryMode.PICKUP) {
        listOf(
            OrderStatus.PENDING to "Order Placed",
            OrderStatus.ACCEPTED to "Confirmed by Station",
            OrderStatus.PREPARING to "Preparing Your Water",
            OrderStatus.READY_FOR_PICKUP to "Ready for Pickup",
            OrderStatus.COMPLETED to "Completed"
        )
    } else {
        listOf(
            OrderStatus.PENDING to "Order Placed",
            OrderStatus.ACCEPTED to "Confirmed by Station",
            OrderStatus.PREPARING to "Preparing Your Water",
            OrderStatus.RIDER_ASSIGNED to "Rider Assigned",
            OrderStatus.OUT_FOR_DELIVERY to "Out for Delivery",
            OrderStatus.DELIVERED to "Delivered",
            OrderStatus.COMPLETED to "Completed"
        )
    }

    val isCancelled = status in setOf(OrderStatus.REJECTED, OrderStatus.CANCELLED)
    val currentIndex = steps.indexOfFirst { it.first == status }.let { if (it == -1) 0 else it }

    val infiniteTransition = rememberInfiniteTransition(label = "timelinePulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, (stepStatus, stepLabel) ->
            val isPassed = !isCancelled && index < currentIndex
            val isCurrent = !isCancelled && index == currentIndex
            val isPending = isCancelled || index > currentIndex

            val dotColor by animateColorAsState(
                targetValue = when {
                    isPassed -> SuccessGreen
                    isCurrent -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                },
                animationSpec = tween(300),
                label = "dotColor"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(36.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(28.dp)
                    ) {
                        if (isCurrent) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha))
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isPassed) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            } else if (isCurrent) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        }
                    }

                    if (index != steps.lastIndex) {
                        val lineColor by animateColorAsState(
                            targetValue = if (isPassed) SuccessGreen else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            animationSpec = tween(300),
                            label = "lineColor"
                        )
                        Box(
                            modifier = Modifier
                                .width(2.5.dp)
                                .height(34.dp)
                                .background(lineColor)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 2.dp, bottom = if (index != steps.lastIndex) 20.dp else 4.dp)
                ) {
                    Text(
                        text = stepLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isCurrent) FontWeight.Bold else if (isPassed) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isPending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                    if (isCurrent) {
                        Text(
                            text = when (status) {
                                OrderStatus.PENDING -> "Station is reviewing your order"
                                OrderStatus.ACCEPTED -> "Station confirmed order"
                                OrderStatus.PREPARING -> "Purifying and filling containers"
                                OrderStatus.READY_FOR_PICKUP -> "Your order is ready at the station"
                                OrderStatus.RIDER_ASSIGNED -> "Delivery partner assigned"
                                OrderStatus.OUT_FOR_DELIVERY -> "Rider is approaching your location"
                                OrderStatus.DELIVERED -> "Delivered to your address"
                                OrderStatus.COMPLETED -> "Order fulfilled successfully"
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }

        if (isCancelled) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = AquaHubShapes.cardSecondary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (status == OrderStatus.REJECTED) "Order was rejected by the station" else "Order was cancelled",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
