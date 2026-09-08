package com.aesprt.aquahub_customer.ui.feature.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.time.LocalTime

@Composable
fun HomeScreen(
    state: CustomerUiState,
    viewModel: CustomerViewModel,
    onStationClick: (PublicStation) -> Unit,
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
        onSearchChange = viewModel::setSearch,
        onFilterChange = viewModel::setStationFilter,
        onRequestLocationAccess = ::requestLocationAccess,
        onPickLocationClick = { showLocationPicker = true },
        onStationClick = onStationClick,
        onOrderClick = onOrderClick,
        onReorder = onReorder,
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
}

@Composable
fun HomeContent(
    state: CustomerUiState,
    onSearchChange: (String) -> Unit,
    onFilterChange: (StationFilter) -> Unit,
    onRequestLocationAccess: () -> Unit,
    onPickLocationClick: () -> Unit,
    onStationClick: (PublicStation) -> Unit,
    onOrderClick: (String) -> Unit,
    onReorder: (CustomerOrder) -> Unit,
    modifier: Modifier = Modifier
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

                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    AquaHubLogo(
                        modifier = Modifier.size(42.dp),
                        contentDescription = "AquaHub"
                    )
                }
            }
        }

        // Location & Search Header Card
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
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.EditLocationAlt,
                                    contentDescription = "Change delivery location",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    AquaSearchBar(
                        query = state.searchQuery,
                        onQueryChange = onSearchChange,
                        placeholder = "Search station, city, or barangay"
                    )
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

        // Stale Cache Banner
        if (state.stationsFromCache) {
            item { StaleCacheBanner() }
        }

        // Filter chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StationFilter.entries.forEach { filter ->
                    val isSelected = state.stationFilter == filter
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            onFilterChange(filter)
                            if (filter == StationFilter.NEAREST && state.location == null) onRequestLocationAccess()
                        },
                        label = { Text(filter.label) },
                        leadingIcon = if (filter == StationFilter.NEAREST) {
                            { Icon(Icons.Outlined.NearMe, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        shape = AquaHubShapes.chip,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    )
                }
            }
        }

        // Section Title
        val displayedStations = state.displayedStations(LocalTime.now())

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Water Refilling Stations",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (state.stationFilter == StationFilter.NEAREST) {
                            "Showing stations within 1 km of your location"
                        } else {
                            "Verified AquaHub partners with quality assurance"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = if (state.location == null) "${displayedStations.size} available" else "${displayedStations.size} nearby",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (state.stations.isEmpty() && state.locationLoading) {
            items(3) {
                StationCardSkeleton()
            }
        } else if (displayedStations.isEmpty()) {
            item {
                val isNearestNoLocation = state.stationFilter == StationFilter.NEAREST && state.location == null
                val isNearestEmpty = state.stationFilter == StationFilter.NEAREST && state.location != null

                val emptyTitle = when {
                    isNearestNoLocation -> "Location needed"
                    isNearestEmpty -> "No stations within 1 km"
                    else -> "No refilling stations found"
                }

                val emptyDescription = when {
                    state.stationsError != null -> state.stationsError
                    isNearestNoLocation -> "Set your delivery address or enable GPS to view water refilling stations within 1 km of you."
                    isNearestEmpty -> "We couldn't find any water stations within 1 km of your current location. Switch to 'All stations' to explore stations in other areas."
                    state.stationFilter == StationFilter.OPEN_NOW -> "No stations are currently open right now. Try switching to 'All stations'."
                    state.searchQuery.isNotEmpty() -> "No stations found matching \"${state.searchQuery}\"."
                    else -> "We couldn't find any water stations matching your current search or location criteria."
                }

                val actionLabel = when {
                    isNearestNoLocation -> "Set Location"
                    state.searchQuery.isNotEmpty() -> "Clear Search"
                    state.stationFilter != StationFilter.ALL -> "Show All Stations"
                    else -> null
                }

                EmptyStateView(
                    title = emptyTitle,
                    description = emptyDescription,
                    icon = Icons.Outlined.Storefront,
                    actionLabel = actionLabel,
                    onAction = {
                        when {
                            isNearestNoLocation -> onRequestLocationAccess()
                            state.searchQuery.isNotEmpty() -> onSearchChange("")
                            else -> onFilterChange(StationFilter.ALL)
                        }
                    }
                )
            }
        } else {
            items(displayedStations, key = { it.id }) { station ->
                StationCard(
                    station = station,
                    origin = state.location,
                    onClick = onStationClick
                )
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
            animation = tween(750, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
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

@Preview(showBackground = true)
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
            onSearchChange = {},
            onFilterChange = {},
            onRequestLocationAccess = {},
            onPickLocationClick = {},
            onStationClick = {},
            onOrderClick = {},
            onReorder = {}
        )
    }
}
