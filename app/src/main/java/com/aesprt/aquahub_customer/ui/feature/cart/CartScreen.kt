package com.aesprt.aquahub_customer.ui.feature.cart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub_customer.domain.*
import com.aesprt.aquahub_customer.ui.CustomerUiState
import com.aesprt.aquahub_customer.ui.CustomerViewModel
import com.aesprt.aquahub_customer.ui.components.*
import com.aesprt.aquahub_customer.ui.theme.*

@Composable
fun CartScreen(
    state: CustomerUiState,
    viewModel: CustomerViewModel,
    onBack: () -> Unit,
    onProceedToCheckout: () -> Unit,
    onExploreStations: () -> Unit,
    modifier: Modifier = Modifier
) {
    CartContent(
        state = state,
        onBack = onBack,
        onProceedToCheckout = onProceedToCheckout,
        onExploreStations = onExploreStations,
        onChangeQuantity = viewModel::changeQuantity,
        onAddProduct = viewModel::addProduct,
        onClearCart = {
            state.cart.forEach { viewModel.changeQuantity(it.product.id, -it.quantity) }
        },
        modifier = modifier
    )
}

@Composable
fun CartContent(
    state: CustomerUiState,
    onBack: () -> Unit,
    onProceedToCheckout: () -> Unit,
    onExploreStations: () -> Unit,
    onChangeQuantity: (String, Int) -> Unit,
    onAddProduct: (com.aesprt.aquahub_customer.domain.PublicProduct) -> Unit,
    onClearCart: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        containerColor = Color.Transparent,
        topBar = {
            AquaCustomerTopBar(
                title = "My Cart",
                subtitle = state.selectedStation?.let { "Ordering from ${it.name}" },
                onBack = onBack,
                actions = {
                    if (state.cart.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteSweep,
                                contentDescription = "Clear cart",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (state.cart.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Estimated Total",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = state.total.format(),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        AquaPrimaryButton(
                            text = "Checkout",
                            onClick = onProceedToCheckout,
                            modifier = Modifier.widthIn(min = 140.dp, max = 170.dp),
                            height = 48.dp,
                            trailingIcon = Icons.AutoMirrored.Outlined.ArrowForward
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (state.cart.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                EmptyStateView(
                    title = "Your cart is empty",
                    description = "Choose a nearby water refilling station and add your refills or containers.",
                    icon = Icons.Outlined.ShoppingCart,
                    actionLabel = "Browse Stations",
                    onAction = onExploreStations
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Station Notice Card
                item {
                    AquaHubGlassCard(shape = AquaHubShapes.cardSecondary) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer, shape = AquaHubShapes.chip),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Storefront,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.selectedStation?.name ?: "AquaHub Station",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "All items in this order are fulfilled by this station",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Cart Items (${state.cartCount})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Cart Line Items
                items(state.cart, key = { it.product.id }) { line ->
                    CartItemCard(
                        cartLine = line,
                        onMinus = { onChangeQuantity(line.product.id, -1) },
                        onPlus = { onAddProduct(line.product) }
                    )
                }

                // Pricing Summary
                item {
                    OrderSummaryCard(
                        subtotal = state.subtotal,
                        deliveryFee = state.deliveryFee,
                        total = state.total
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // Clear Cart Dialog
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = {
                    Text(
                        text = "Clear Cart?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to remove all items from your cart?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onClearCart()
                            showClearDialog = false
                        }
                    ) {
                        Text("Clear", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CartScreenPreview() {
    val sampleStation = PublicStation(
        id = "st-1",
        businessId = "biz-1",
        name = "aquaman Refilling Station",
        phone = "09123456789",
        address = "Bansalangin, Quezon City",
        location = com.aesprt.aquahub_customer.domain.GeoPoint(14.66, 121.02),
        isAcceptingOrders = true,
        openingTime = "07:00",
        closingTime = "20:00",
        deliveryRadiusKm = 5.0,
        deliveryFee = com.aesprt.aquahub_customer.domain.Money.fromPesos(25),
        estimatedPreparationMinutes = 30
    )
    val sampleProduct = PublicProduct(
        id = "p-1",
        name = "5 Gallon Round (Refill)",
        type = com.aesprt.aquahub_customer.domain.ProductType.REFILL,
        price = com.aesprt.aquahub_customer.domain.Money.fromPesos(50),
        description = "Purified water refill",
        sizeLabel = "20 Litters",
        imagePath = null,
        isAvailable = true
    )
    val sampleState = CustomerUiState(
        selectedStation = sampleStation,
        cart = listOf(com.aesprt.aquahub_customer.domain.CartLine(sampleProduct, 2))
    )
    AquaHubCustomerTheme(dynamicColor = false) {
        CartContent(
            state = sampleState,
            onBack = {},
            onProceedToCheckout = {},
            onExploreStations = {},
            onChangeQuantity = { _, _ -> },
            onAddProduct = {},
            onClearCart = {}
        )
    }
}
