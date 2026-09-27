package com.aesprt.aquahub_customer.ui.feature.station

import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Star
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
import com.aesprt.aquahub_customer.domain.*
import com.aesprt.aquahub_customer.ui.CustomerUiState
import com.aesprt.aquahub_customer.ui.CustomerViewModel
import com.aesprt.aquahub_customer.ui.components.*
import com.aesprt.aquahub_customer.ui.theme.*
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationDetailScreen(
    state: CustomerUiState,
    viewModel: CustomerViewModel,
    onBack: () -> Unit,
    onViewCart: () -> Unit,
    modifier: Modifier = Modifier
) {
    StationDetailContent(
        state = state,
        onBack = onBack,
        onViewCart = onViewCart,
        onAddProduct = viewModel::addProduct,
        onChangeQuantity = viewModel::changeQuantity,
        onSetDeliveryMode = viewModel::setDeliveryMode,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationDetailContent(
    state: CustomerUiState,
    onBack: () -> Unit,
    onViewCart: () -> Unit,
    onAddProduct: (PublicProduct) -> Unit,
    onChangeQuantity: (String, Int) -> Unit,
    onSetDeliveryMode: (DeliveryMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val station = state.selectedStation ?: return
    val context = LocalContext.current

    var selectedType by remember { mutableStateOf<ProductType?>(null) }
    var configuringProduct by remember { mutableStateOf<PublicProduct?>(null) }

    val filteredProducts = remember(state.products, selectedType) {
        state.products.filter { selectedType == null || it.type == selectedType }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                AquaCustomerTopBar(
                    title = station.name,
                    subtitle = station.address,
                    onBack = onBack,
                    actions = {
                        IconButton(onClick = onViewCart) {
                            BadgedBox(
                                badge = {
                                    if (state.cartCount > 0) {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                        ) {
                                            Text(state.cartCount.toString())
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ShoppingCart,
                                    contentDescription = "Cart",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = state.cart.isNotEmpty(),
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        shape = AquaHubShapes.button,
                        color = MaterialTheme.colorScheme.primary,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onViewCart)
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${state.cartCount} items in cart",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                                )
                                Text(
                                    text = state.subtotal.format(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "View Cart",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Station Hero Card
                item {
                    AquaHubGlassCard(shape = AquaHubShapes.cardPrimary) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StationStatusChip(isOpen = station.isOpenAt(LocalTime.now()))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Star,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    val ratingText = if (station.averageRating != null) {
                                        "${String.format(java.util.Locale.US, "%.1f", station.averageRating)} (${station.ratingCount} reviews)"
                                    } else {
                                        "No ratings yet"
                                    }
                                    Text(
                                        text = ratingText,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            Text(
                                text = station.name,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Text(
                                text = station.address,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Quick Action Chips (Hours, Call)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.AccessTime,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "${station.openingTime ?: "7:00 AM"} – ${station.closingTime ?: "7:00 PM"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                if (station.phone.isNotBlank()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${station.phone}"))
                                                context.startActivity(intent)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Call,
                                                contentDescription = "Call station",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "Call",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Fulfillment Mode Selector (Delivery vs Pickup)
                item {
                    AquaSegmentedControl(
                        items = listOf(DeliveryMode.DELIVERY, DeliveryMode.PICKUP),
                        selectedItem = state.deliveryMode,
                        onItemSelected = onSetDeliveryMode,
                        itemLabel = { mode ->
                            when (mode) {
                                DeliveryMode.DELIVERY -> "Delivery"
                                DeliveryMode.PICKUP -> "Station Pickup"
                            }
                        },
                        itemIcon = { mode ->
                            when (mode) {
                                DeliveryMode.DELIVERY -> Icons.Outlined.LocalShipping
                                DeliveryMode.PICKUP -> Icons.Outlined.Storefront
                            }
                        },
                        itemBadge = { mode ->
                            when (mode) {
                                DeliveryMode.DELIVERY -> if (station.deliveryFee.centavos == 0L) "Free" else "₱${station.deliveryFee.centavos / 100}"
                                DeliveryMode.PICKUP -> "Free"
                            }
                        }
                    )
                }

                // Stale banner if applicable
                if (state.productsFromCache) {
                    item { StaleCacheBanner() }
                }

                // Categories Row
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            FilterChip(
                                selected = selectedType == null,
                                onClick = { selectedType = null },
                                label = { Text("All Products") },
                                shape = AquaHubShapes.chip,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                )
                            )
                        }

                        items(ProductType.entries) { type ->
                            FilterChip(
                                selected = selectedType == type,
                                onClick = { selectedType = type },
                                label = { Text(type.label) },
                                shape = AquaHubShapes.chip,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                )
                            )
                        }
                    }
                }

                // Product List Header
                item {
                    Text(
                        text = "Available Water & Products",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (state.products.isEmpty() && state.locationLoading) {
                    items(3) {
                        ProductCardSkeleton()
                    }
                } else if (filteredProducts.isEmpty()) {
                    item {
                        EmptyStateView(
                            title = "No products found",
                            description = "This station hasn't published products in this category yet.",
                            icon = Icons.Outlined.WaterDrop
                        )
                    }
                } else {
                    items(filteredProducts, key = { it.id }) { product ->
                        val currentQty = state.cart.firstOrNull { it.product.id == product.id }?.quantity ?: 0
                        ProductCard(
                            product = product,
                            currentQuantity = currentQty,
                            onAdd = { onAddProduct(product) },
                            onMinus = { onChangeQuantity(product.id, -1) },
                            onPlus = { onAddProduct(product) },
                            onConfigure = { configuringProduct = product }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(60.dp)) }
            }
        }

        // Product Configuration Bottom Sheet
        configuringProduct?.let { product ->
            ProductDetailBottomSheet(
                product = product,
                allProducts = state.products,
                onDismiss = { configuringProduct = null },
                onAddToCart = { selectedProduct, quantity, _ ->
                    repeat(quantity) {
                        onAddProduct(selectedProduct)
                    }
                }
            )
        }
    }
}

@Preview(name = "Light Mode", showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun StationDetailScreenPreview() {
    val sampleStation = PublicStation(
        id = "st-1",
        businessId = "biz-1",
        name = "aquaman Refilling Station",
        phone = "09123456789",
        address = "Bansalangin, Quezon City",
        location = GeoPoint(14.66, 121.02),
        isAcceptingOrders = true,
        openingTime = "07:00",
        closingTime = "20:00",
        deliveryRadiusKm = 5.0,
        deliveryFee = Money.fromPesos(25),
        estimatedPreparationMinutes = 30
    )
    val sampleProducts = listOf(
        PublicProduct(
            id = "p-1",
            name = "5 Gallon Round (Refill)",
            type = ProductType.REFILL,
            price = Money.fromPesos(50),
            description = "Clean, purified drinking water refill",
            sizeLabel = "20 Litters",
            imagePath = null,
            isAvailable = true
        ),
        PublicProduct(
            id = "p-2",
            name = "5 Gallon Slim with Faucet",
            type = ProductType.NEW_CONTAINER,
            price = Money.fromPesos(220),
            description = "Brand new food-grade container with built-in tap",
            sizeLabel = "20 Litters",
            imagePath = null,
            isAvailable = true
        )
    )
    val sampleState = CustomerUiState(
        selectedStation = sampleStation,
        products = sampleProducts
    )
    AquaHubCustomerTheme(dynamicColor = false) {
        StationDetailContent(
            state = sampleState,
            onBack = {},
            onViewCart = {},
            onAddProduct = {},
            onChangeQuantity = { _, _ -> },
            onSetDeliveryMode = {}
        )
    }
}
