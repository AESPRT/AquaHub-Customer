package com.aesprt.aquahub_customer.ui.feature.checkout

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import coil.compose.AsyncImage

@Composable
fun CheckoutScreen(
    state: CustomerUiState,
    viewModel: CustomerViewModel,
    onBack: () -> Unit,
    onOrderPlaced: (CustomerOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    var showLocationPicker by remember { mutableStateOf(false) }
    var pendingQrDownload by remember { mutableStateOf<Pair<String, String>?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveQrLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val download = pendingQrDownload
        if (uri != null && download != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        URL(download.first).openStream().use { input ->
                            requireNotNull(context.contentResolver.openOutputStream(uri)).use { output -> input.copyTo(output) }
                        }
                    }
                }.onSuccess { viewModel.showMessage("Payment QR saved to your device.") }
                    .onFailure { viewModel.showMessage("Could not save the QR image. Please try again.") }
                pendingQrDownload = null
            }
        }
    }

    CheckoutContent(
        state = state,
        onBack = onBack,
        onSubmitOrder = { viewModel.submitOrder(onOrderPlaced) },
        onSetDeliveryMode = viewModel::setDeliveryMode,
        onSetPaymentMethod = viewModel::setPaymentMethod,
        onSetCustomerNote = viewModel::setCustomerNote,
        onSetBusinessRulesRead = viewModel::setBusinessRulesRead,
        onSetBusinessRulesAccepted = viewModel::setBusinessRulesAccepted,
        onPickLocationClick = { showLocationPicker = true },
        onSavePaymentQr = { url, wallet ->
            pendingQrDownload = url to "${state.selectedStation?.name.orEmpty()}_${wallet}_QR.png"
            saveQrLauncher.launch(pendingQrDownload!!.second)
        },
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
    onSetPaymentMethod: (PaymentMethod) -> Unit,
    onSetCustomerNote: (String) -> Unit,
    onSetBusinessRulesRead: (Boolean) -> Unit,
    onSetBusinessRulesAccepted: (Boolean) -> Unit,
    onPickLocationClick: () -> Unit,
    onSavePaymentQr: (String, String) -> Unit,
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
                    if (!state.canPlaceOrder && state.selectedStation != null) {
                        Text(
                            text = if (rulesText.isBlank()) {
                                "Ordering is locked until this station publishes its rules."
                            } else {
                                "Review and accept the station rules at the top of checkout."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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

            item {
                StationRulesAgreementCard(
                    stationName = state.selectedStation?.name,
                    rulesText = rulesText,
                    rulesScrollState = rulesScrollState,
                    rulesRead = state.businessRulesRead,
                    rulesAccepted = state.businessRulesAccepted,
                    onRulesAcceptedChange = onSetBusinessRulesAccepted,
                )
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

                        Box(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = state.address,
                                onValueChange = {},
                                modifier = Modifier.fillMaxWidth(),
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
                                    Icon(Icons.Outlined.Map, contentDescription = "Open map location search")
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable(onClick = onPickLocationClick, role = Role.Button),
                            )
                        }
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

                    if (state.availablePaymentMethods.isEmpty()) {
                        Surface(shape = AquaHubShapes.cardSecondary, color = MaterialTheme.colorScheme.errorContainer) {
                            Text("This station has no payment method available for this fulfilment option.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    } else state.availablePaymentMethods.forEach { method ->
                        val selected = state.selectedPaymentMethod == method
                        val station = state.selectedStation
                        val accountName = when (method) {
                            PaymentMethod.GCASH -> station?.gcashAccountName
                            PaymentMethod.MAYA -> station?.mayaAccountName
                            else -> null
                        }
                        val accountNumber = when (method) {
                            PaymentMethod.GCASH -> station?.gcashAccountNumber
                            PaymentMethod.MAYA -> station?.mayaAccountNumber
                            else -> null
                        }
                        val qrUrl = when (method) {
                            PaymentMethod.GCASH -> station?.gcashQrImagePath
                            PaymentMethod.MAYA -> station?.mayaQrImagePath
                            else -> null
                        }
                        Surface(
                            onClick = { onSetPaymentMethod(method) },
                            shape = RoundedCornerShape(20.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(if (method == PaymentMethod.GCASH || method == PaymentMethod.MAYA) Icons.Outlined.AccountBalanceWallet else Icons.Outlined.Payments, null, tint = MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f)) {
                                        Text(method.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        Text(if (method == PaymentMethod.CASH_ON_DELIVERY) "Pay the rider when your order arrives" else if (method == PaymentMethod.CASH) "Pay at the station when collecting your order" else listOfNotNull(accountName, accountNumber).joinToString(" · ").ifBlank { "Pay using the station's wallet account" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    RadioButton(selected = selected, onClick = { onSetPaymentMethod(method) })
                                }
                                if (selected && qrUrl != null) {
                                    AsyncImage(model = qrUrl, contentDescription = "${method.label} payment QR", modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(12.dp))
                                    OutlinedButton(onClick = { onSavePaymentQr(qrUrl, method.label) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                                        Icon(Icons.Outlined.Download, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Save QR to device")
                                    }
                                }
                            }
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
                    subtotal = state.regularSubtotal,
                    deliveryFee = state.deliveryFee,
                    discount = state.promotionSavings,
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

@Composable
private fun StationRulesAgreementCard(
    stationName: String?,
    rulesText: String,
    rulesScrollState: androidx.compose.foundation.ScrollState,
    rulesRead: Boolean,
    rulesAccepted: Boolean,
    onRulesAcceptedChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Gavel,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Station rules — required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Review ${stationName ?: "this station"}'s terms before placing your order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (rulesText.isBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = AquaHubShapes.cardSecondary,
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = "This station has not published its rules yet. Ordering remains locked until the owner publishes them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            return@Column
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp, max = 280.dp)
                .verticalScroll(rulesScrollState),
            shape = AquaHubShapes.cardSecondary,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = rulesText,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 21.sp,
            )
        }
        Text(
            text = if (rulesRead) {
                "Rules reviewed. Confirm your acceptance below."
            } else {
                "Scroll through the complete rules to enable acceptance."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = rulesRead,
                    role = Role.Checkbox,
                    onClick = { onRulesAcceptedChange(!rulesAccepted) },
                ),
            shape = AquaHubShapes.cardSecondary,
            color = if (rulesAccepted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (rulesAccepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = rulesAccepted,
                    onCheckedChange = onRulesAcceptedChange,
                    enabled = rulesRead,
                )
                Text(
                    text = "I have read and agree to this station's rules.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (rulesRead) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(name = "Light Mode", showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
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
            onSetPaymentMethod = {},
            onSetCustomerNote = {},
            onSetBusinessRulesRead = {},
            onSetBusinessRulesAccepted = {},
            onPickLocationClick = {},
            onSavePaymentQr = { _, _ -> }
        )
    }
}
