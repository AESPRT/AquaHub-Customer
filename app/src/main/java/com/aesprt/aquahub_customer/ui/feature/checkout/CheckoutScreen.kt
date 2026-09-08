package com.aesprt.aquahub_customer.ui.feature.checkout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub_customer.domain.CustomerOrder
import com.aesprt.aquahub_customer.domain.DeliveryMode
import com.aesprt.aquahub_customer.domain.PaymentMethod
import com.aesprt.aquahub_customer.ui.CustomerUiState
import com.aesprt.aquahub_customer.ui.CustomerViewModel
import com.aesprt.aquahub_customer.ui.components.*
import com.aesprt.aquahub_customer.ui.theme.*
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun CheckoutScreen(
    state: CustomerUiState,
    viewModel: CustomerViewModel,
    onBack: () -> Unit,
    onOrderPlaced: (CustomerOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    var showLocationPicker by remember { mutableStateOf(false) }

    CheckoutContent(
        state = state,
        onBack = onBack,
        onSubmitOrder = { viewModel.submitOrder(onOrderPlaced) },
        onSetDeliveryMode = viewModel::setDeliveryMode,
        onSetCustomerNote = viewModel::setCustomerNote,
        onSetBusinessRulesRead = viewModel::setBusinessRulesRead,
        onSetBusinessRulesAccepted = viewModel::setBusinessRulesAccepted,
        onPickLocationClick = { showLocationPicker = true },
        modifier = modifier
    )

    if (showLocationPicker) {
        CustomerLocationPickerDialog(
            state = state,
            viewModel = viewModel,
            title = "Set delivery address",
            initialAddress = state.address.ifBlank { null },
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
fun CheckoutContent(
    state: CustomerUiState,
    onBack: () -> Unit,
    onSubmitOrder: () -> Unit,
    onSetDeliveryMode: (DeliveryMode) -> Unit,
    onSetCustomerNote: (String) -> Unit,
    onSetBusinessRulesRead: (Boolean) -> Unit,
    onSetBusinessRulesAccepted: (Boolean) -> Unit,
    onPickLocationClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isAsapSchedule by remember { mutableStateOf(true) }
    var selectedDateMillis by remember { mutableStateOf<Long?>(null) }
    var selectedHour by remember { mutableStateOf<Int?>(null) }
    var selectedMinute by remember { mutableStateOf<Int?>(null) }
    val rulesText = state.selectedStation?.businessRulesText?.trim().orEmpty()
    val rulesScrollState = rememberScrollState()

    LaunchedEffect(state.selectedStation?.id, state.selectedStation?.businessRulesVersion) {
        rulesScrollState.scrollTo(0)
        if (rulesText.isNotBlank()) {
            snapshotFlow { !rulesScrollState.canScrollForward }
                .distinctUntilChanged()
                .collect { reachedEnd -> onSetBusinessRulesRead(reachedEnd) }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        containerColor = Color.Transparent,
        topBar = {
            AquaCustomerTopBar(
                title = "Checkout",
                subtitle = state.selectedStation?.name,
                onBack = onBack
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 12.dp,
                shape = AquaHubShapes.bottomSheet
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AquaPrimaryButton(
                        text = "Place Order · ${state.total.format()}",
                        onClick = onSubmitOrder,
                        enabled = !state.submitting && state.cart.isNotEmpty() && state.isDeliveryInRange && state.canPlaceOrder,
                        loading = state.submitting,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Fulfilment Mode: Delivery vs Pickup
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Fulfilment Option",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    AquaSegmentedControl(
                        items = listOf(DeliveryMode.DELIVERY, DeliveryMode.PICKUP),
                        selectedItem = state.deliveryMode,
                        onItemSelected = { mode -> onSetDeliveryMode(mode) },
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
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Address Details (for delivery mode)
            if (state.deliveryMode == DeliveryMode.DELIVERY) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Delivery Address",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        OutlinedTextField(
                            value = state.address,
                            onValueChange = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onPickLocationClick),
                            readOnly = true,
                            minLines = 2,
                            shape = AquaHubShapes.cardSecondary,
                            label = { Text("Complete Address") },
                            placeholder = { Text("Search and pin a delivery location") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.LocationOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = onPickLocationClick) {
                                    Icon(Icons.Outlined.Map, contentDescription = "Open map location search")
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            )
                        )
                    }
                }

                if (!state.isDeliveryInRange) {
                    item {
                        Surface(
                            shape = AquaHubShapes.cardSecondary,
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column {
                                    Text(
                                        text = "Outside Delivery Area",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    val radiusKm = state.selectedStation?.deliveryRadiusKm ?: 0.0
                                    Text(
                                        text = "This station delivers up to $radiusKm km. Please switch to Station Pickup or choose a closer station.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    AquaHubGlassCard(shape = AquaHubShapes.cardSecondary) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Storefront,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = "Pickup Location",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = state.selectedStation?.address ?: "Station address",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Schedule Options
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Preferred Schedule",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    AquaSegmentedControl(
                        items = listOf(true, false),
                        selectedItem = isAsapSchedule,
                        onItemSelected = { asap -> isAsapSchedule = asap },
                        itemLabel = { asap ->
                            if (asap) "Deliver ASAP (~${state.estimatedEtaMinutes} min)" else "Schedule for Later"
                        },
                        itemIcon = { asap ->
                            if (asap) Icons.Rounded.Schedule else Icons.Rounded.CalendarMonth
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (!isAsapSchedule) {
                        Spacer(modifier = Modifier.height(4.dp))
                        AquaCustomerDatePickerField(
                            selectedDateMillis = selectedDateMillis,
                            onDateSelected = { selectedDateMillis = it }
                        )

                        Spacer(modifier = Modifier.height(6.dp))
                        AquaCustomerTimePickerField(
                            selectedHour = selectedHour,
                            selectedMinute = selectedMinute,
                            onTimeSelected = { h, m ->
                                selectedHour = h
                                selectedMinute = m
                            }
                        )
                    }
                }
            }

            // Payment Method
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Payment Method",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    AquaHubGlassCard(shape = AquaHubShapes.cardSecondary) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (state.deliveryMode == DeliveryMode.DELIVERY) "Cash on Delivery (COD)" else "Cash on Pickup",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Pay in cash upon receiving your water containers",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (rulesText.isNotBlank()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Station Rules / Terms & Conditions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 280.dp)
                                .verticalScroll(rulesScrollState),
                            shape = AquaHubShapes.cardSecondary,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = rulesText,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = 21.sp
                            )
                        }
                        Text(
                            text = if (state.businessRulesRead) {
                                "You reached the end of the station rules. You can now accept them."
                            } else {
                                "Scroll to the end of the station rules to enable the checkbox."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = state.businessRulesAccepted,
                                onCheckedChange = onSetBusinessRulesAccepted,
                                enabled = state.businessRulesRead
                            )
                            Text(
                                text = "I have read and agree to this station's rules.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state.businessRulesRead) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }

            if (rulesText.isBlank() && state.selectedStation != null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = AquaHubShapes.cardSecondary,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "This station has not published its rules yet. You cannot place an order until the station owner publishes them.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Customer Notes Field
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Delivery Instructions (Optional)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    OutlinedTextField(
                        value = state.customerNote,
                        onValueChange = onSetCustomerNote,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        shape = AquaHubShapes.cardSecondary,
                        placeholder = { Text("e.g. Please ring the doorbell, empty bottles are by the gate") },
                        supportingText = { Text("${state.customerNote.length}/500") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }
            }

            // Order Breakdown
            item {
                OrderSummaryCard(
                    subtotal = state.subtotal,
                    deliveryFee = state.deliveryFee,
                    total = state.total
                )
            }

            item {
                Text(
                    text = "By placing your order, you agree to AquaHub's standard terms of delivery and container handling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CheckoutScreenPreview() {
    val sampleStation = com.aesprt.aquahub_customer.domain.PublicStation(
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
    val sampleProduct = com.aesprt.aquahub_customer.domain.PublicProduct(
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
        address = "P36R+RPV, Bansalangin, Quezon City",
        location = com.aesprt.aquahub_customer.domain.GeoPoint(14.66, 121.02),
        cart = listOf(com.aesprt.aquahub_customer.domain.CartLine(sampleProduct, 2)),
        deliveryMode = DeliveryMode.DELIVERY
    )
    AquaHubCustomerTheme(dynamicColor = false) {
        CheckoutContent(
            state = sampleState,
            onBack = {},
            onSubmitOrder = {},
            onSetDeliveryMode = {},
            onSetCustomerNote = {},
            onSetBusinessRulesRead = {},
            onSetBusinessRulesAccepted = {},
            onPickLocationClick = {}
        )
    }
}
