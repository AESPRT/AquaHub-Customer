package com.aesprt.aquahub_customer.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub_customer.domain.GeoPoint
import com.aesprt.aquahub_customer.domain.PublicStation
import com.aesprt.aquahub_customer.ui.theme.*
import coil.compose.AsyncImage
import java.time.LocalTime

@Composable
fun StationCard(
    station: PublicStation,
    origin: GeoPoint?,
    onClick: (PublicStation) -> Unit,
    modifier: Modifier = Modifier
) {
    AquaHubGlassCard(
        modifier = modifier.fillMaxWidth(),
        onClick = { onClick(station) },
        shape = AquaHubShapes.cardPrimary,
        elevation = 3.dp,
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Station Water Icon Container
            Box(
                modifier = Modifier
                    .size(66.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (!station.logoPath.isNullOrBlank()) {
                    AsyncImage(
                        model = station.logoPath,
                        contentDescription = "${station.name} station image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Storefront,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Header: Name + Status Chip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = station.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StationStatusChip(isOpen = station.isOpenAt(LocalTime.now()))
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = station.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Station Metrics Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dist = station.distanceKmFrom(origin)
                    StationMetric(
                        icon = Icons.Outlined.LocationOn,
                        text = if (dist != null) String.format(java.util.Locale.US, "%.1f km", dist) else "Nearby"
                    )

                    StationMetric(
                        icon = Icons.Outlined.AccessTime,
                        text = "~${station.estimatedPreparationMinutes} min"
                    )

                    // Delivery fee / badge
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    ) {
                        Text(
                            text = if (station.deliveryFee.centavos == 0L) "Free delivery" else "₱${station.deliveryFee.centavos / 100} delivery",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StationMetric(
    icon: ImageVector,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

@Preview(name = "Light Mode", showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StationCardPreview() {
    val sampleStation = PublicStation(
        id = "st-1",
        businessId = "biz-1",
        name = "AquaPure Refilling Station",
        phone = "09171234567",
        address = "123 Katipunan Ave, Quezon City",
        location = GeoPoint(14.65, 121.07),
        isAcceptingOrders = true,
        openingTime = "07:00",
        closingTime = "19:00",
        deliveryRadiusKm = 5.0,
        deliveryFee = com.aesprt.aquahub_customer.domain.Money.fromPesos(25),
        estimatedPreparationMinutes = 30
    )

    AquaHubCustomerTheme(dynamicColor = false) {
        Box(modifier = Modifier.padding(16.dp)) {
            StationCard(
                station = sampleStation,
                origin = GeoPoint(14.64, 121.06),
                onClick = {}
            )
        }
    }
}
