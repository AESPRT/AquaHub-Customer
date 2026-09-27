package com.aesprt.aquahub_customer.ui.feature.home

import android.content.res.Configuration
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aesprt.aquahub_customer.domain.CustomerOrder
import com.aesprt.aquahub_customer.domain.PublicStation
import com.aesprt.aquahub_customer.ui.CustomerUiState
import com.aesprt.aquahub_customer.ui.CustomerViewModel
import com.aesprt.aquahub_customer.ui.StationFilter
import com.aesprt.aquahub_customer.ui.components.*
import com.aesprt.aquahub_customer.ui.theme.*

@Composable
fun HomeScreen(
    state: CustomerUiState,
    viewModel: CustomerViewModel,
    onStationClick: (PublicStation) -> Unit,
    onConfirmStationChange: (PublicStation) -> Unit,
    onOrderClick: (String) -> Unit,
    onReorder: (CustomerOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            viewModel.refreshLocation()
        } else {
            viewModel.setLocation(null, "Location off · Search below")
        }
    }

    fun requestLocationAccess() {
        if (hasLocationPermission()) {
            viewModel.refreshLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
            )
        }
    }

    LaunchedEffect(Unit) { requestLocationAccess() }

    var showLocationPicker by remember { mutableStateOf(false) }

    HomeContent(
        state = state,
        onRequestLocationAccess = ::requestLocationAccess,
        onPickLocationClick = { showLocationPicker = true },
        onStationClick = onStationClick,
        onOrderClick = onOrderClick,
        onReorder = onReorder,
        onRetryStation = viewModel::retryLinkedStation,
        modifier = modifier
    )

    if (showLocationPicker) {
        CustomerLocationPickerDialog(
            state = state,
            viewModel = viewModel,
            title = "Choose your delivery area",
            initialAddress = state.address.ifBlank { state.locationLabel },
            initialLocation = state.location,
            onDismiss = { showLocationPicker = false },
            onConfirm = { location ->
                viewModel.setDeliveryLocation(location)
                showLocationPicker = false
            },
        )
    }

    state.pendingStationSelection?.let { station ->
        AlertDialog(
            onDismissRequest = viewModel::cancelStationChange,
            title = { Text("Switch water station?") },
            text = {
                Text(
                    "Your current cart is for ${state.selectedStation?.name ?: "another station"}. " +
                        "Switching to ${station.name} will clear those items.",
                )
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelStationChange) { Text("Keep cart") }
            },
            confirmButton = {
                Button(onClick = { onConfirmStationChange(station) }) { Text("Switch station") }
            },
        )
    }
}

@Composable
fun HomeContent(
    modifier: Modifier = Modifier,
    state: CustomerUiState,
    onRequestLocationAccess: () -> Unit,
    onPickLocationClick: () -> Unit,
    onStationClick: (PublicStation) -> Unit,
    onOrderClick: (String) -> Unit,
    onReorder: (CustomerOrder) -> Unit,
    onRetryStation: () -> Unit = {}
) {
    val activeOrder = remember(state.orders) { state.orders.firstOrNull { !it.status.isTerminal } }
    val lastCompletedOrder = remember(state.orders) { state.orders.firstOrNull { it.status.isTerminal } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header: Greeting + User Avatar
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 16.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Good day, ${state.profile?.displayName?.substringBefore(' ') ?: "Neighbor"}",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Fresh drinking water, delivered quickly.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Delivery location for the linked station
        item {
            AquaHubGlassCard(
                shape = AquaHubShapes.cardPrimary,
                onClick = onPickLocationClick,
                backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Deliver to",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = state.locationLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(
                            onClick = onRequestLocationAccess
                        ) {
                            if (state.locationLoading) {
                                AquaLoadingIndicator(Modifier.size(22.dp), size = 22.dp, strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.EditLocationAlt,
                                    contentDescription = "Change delivery location",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Active Order Banner (if any)
        if (activeOrder != null) {
            item {
                ActiveLiveOrderBanner(
                    order = activeOrder,
                    onClick = { onOrderClick(activeOrder.id) }
                )
            }
        }

        // Quick Reorder Card (if repeat customer and no active order)
        if (lastCompletedOrder != null && activeOrder == null) {
            item {
                QuickReorderCard(
                    order = lastCompletedOrder,
                    onReorder = onReorder
                )
            }
        }

        val preferredStation = state.preferredStation
        if (preferredStation != null) {
            item {
                AquaHubGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AquaHubShapes.cardPrimary,
                    backgroundColor = MaterialTheme.colorScheme.surface,
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "YOUR LINKED STATION",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 1.1.sp,
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (preferredStation.isAcceptingOrders) {
                                    Success.copy(alpha = 0.12f)
                                } else {
                                    MaterialTheme.colorScheme.errorContainer
                                },
                            ) {
                                Text(
                                    if (preferredStation.isAcceptingOrders) "Accepting orders" else "Currently closed",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (preferredStation.isAcceptingOrders) Success else MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.Storefront, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                                Text(
                                    text = preferredStation.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                Text(
                                    text = preferredStation.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Button(
                            onClick = { onStationClick(preferredStation) },
                            enabled = preferredStation.isAcceptingOrders,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Outlined.WaterDrop, contentDescription = null)
                            Text("View products & order", modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            item {
                LinkedStationUnavailableCard(
                    message = state.stationsError
                        ?: if (state.stationsFromCache) {
                            "Your station isn't stored on this device yet. Connect once to finish syncing it."
                        } else {
                            "We couldn't load your station. Check your connection and try again."
                        },
                    onRetry = onRetryStation,
                )
            }
        }

        // Stale Cache Banner
        if (state.stationsFromCache && preferredStation != null) {
            item { StaleCacheBanner() }
        }

    }
}

@Composable
private fun LinkedStationUnavailableCard(
    message: String,
    onRetry: () -> Unit,
) {
    AquaHubGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = AquaHubShapes.cardPrimary,
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
            Text(
                "Station temporarily unavailable",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Try again", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ActiveLiveOrderBanner(
    order: CustomerOrder,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "liveDot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveDotAlpha"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AquaHubShapes.cardPrimary)
            .clickable(onClick = onClick),
        shape = AquaHubShapes.cardPrimary,
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(aquaHeroGradient())
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = dotAlpha))
                        )
                        Text(
                            text = "ACTIVE ORDER #${order.orderNumber}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.95f),
                            letterSpacing = 1.2.sp
                        )
                    }
                    OrderStatusChip(status = order.status)
                }

                Text(
                    text = order.stationName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${order.items.sumOf { it.quantity }} items · ${order.total.format()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Track order",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
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
private fun HomeScreenPreview() {
    val sampleStation = PublicStation(
        id = "st-1",
        businessId = "biz-1",
        name = "aquaman",
        phone = "09123456789",
        address = "Bansalangin, Quezon City, Kalakhang Maynila, Philippines",
        location = com.aesprt.aquahub_customer.domain.GeoPoint(14.66, 121.02),
        isAcceptingOrders = true,
        openingTime = "07:00",
        closingTime = "20:00",
        deliveryRadiusKm = 5.0,
        deliveryFee = com.aesprt.aquahub_customer.domain.Money.fromPesos(25),
        estimatedPreparationMinutes = 30
    )
    val sampleState = CustomerUiState(
        location = com.aesprt.aquahub_customer.domain.GeoPoint(14.66, 121.02),
        locationLabel = "P36R+RPV, Bansalangin, Quezon ...",
        stations = listOf(sampleStation),
        stationFilter = StationFilter.NEAREST
    )
    AquaHubCustomerTheme(dynamicColor = false) {
        HomeContent(
            state = sampleState,
            onRequestLocationAccess = {},
            onPickLocationClick = {},
            onStationClick = {},
            onOrderClick = {},
            onReorder = {}
        )
    }
}
