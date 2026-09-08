package com.aesprt.aquahub_customer.ui.feature.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub_customer.domain.*
import com.aesprt.aquahub_customer.ui.CustomerUiState
import com.aesprt.aquahub_customer.ui.CustomerViewModel
import com.aesprt.aquahub_customer.ui.components.AquaCustomerTopBar
import com.aesprt.aquahub_customer.ui.components.AquaHubGlassCard
import com.aesprt.aquahub_customer.ui.components.AquaPrimaryButton
import com.aesprt.aquahub_customer.ui.components.AquaSecondaryButton
import com.aesprt.aquahub_customer.ui.components.CustomerLocationPickerDialog
import com.aesprt.aquahub_customer.ui.theme.*

@Composable
fun ProfileScreen(
    state: CustomerUiState,
    viewModel: CustomerViewModel,
    modifier: Modifier = Modifier
) {
    ProfileContent(
        state = state,
        onUpdateProfile = { name, phone -> viewModel.updateProfile(name, phone) },
        onSelectAddress = { index -> viewModel.selectSavedAddress(index) },
        onDeleteAddress = { index -> viewModel.deleteSavedAddress(index) },
        onAddAddress = { address -> viewModel.addSavedAddress(address) },
        onSignOut = { viewModel.signOut() },
        locationPickerDialog = { initialAddress, initialLocation, onDismiss, onConfirm ->
            CustomerLocationPickerDialog(
                state = state,
                viewModel = viewModel,
                title = "Find saved address",
                initialAddress = initialAddress,
                initialLocation = initialLocation,
                onDismiss = onDismiss,
                onConfirm = onConfirm
            )
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileContent(
    state: CustomerUiState,
    onUpdateProfile: (name: String, phone: String) -> Unit,
    onSelectAddress: (Int) -> Unit,
    onDeleteAddress: (Int) -> Unit,
    onAddAddress: (DeliveryAddress) -> Unit,
    onSignOut: () -> Unit,
    locationPickerDialog: (@Composable (
        initialAddress: String?,
        initialLocation: GeoPoint?,
        onDismiss: () -> Unit,
        onConfirm: (ResolvedLocation) -> Unit
    ) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var name by remember(state.profile?.displayName) {
        mutableStateOf(state.profile?.displayName.orEmpty())
    }
    var phone by remember(state.profile?.phone) {
        mutableStateOf(state.profile?.phone.orEmpty())
    }
    var isEditingProfile by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showAddAddressDialog by remember { mutableStateOf(false) }
    var showAddressLocationPicker by remember { mutableStateOf(false) }
    var showSupportDialog by remember { mutableStateOf(false) }

    // Add address form state
    var newAddressLabel by remember { mutableStateOf("Home") }
    var newAddressLine by remember { mutableStateOf("") }
    var newAddressLocation by remember { mutableStateOf<ResolvedLocation?>(null) }

    val hasUnsavedChanges = (name != (state.profile?.displayName ?: "")) ||
        (phone != (state.profile?.phone ?: ""))

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        containerColor = Color.Transparent,
        topBar = {
            AquaCustomerTopBar(
                title = "My Account",
                subtitle = "Manage profile, saved addresses & settings"
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Profile Hero Card
            item {
                Surface(
                    shape = AquaHubShapes.cardPrimary,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 6.dp
                ) {
                    Box(
                        modifier = Modifier
                            .background(aquaHeroGradient())
                            .padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f))
                                    .border(1.5.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.35f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = state.profile?.displayName?.take(1)?.uppercase() ?: "A",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.profile?.displayName?.ifBlank { "AquaHub Customer" } ?: "AquaHub Customer",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = state.profile?.email.orEmpty(),
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                                    style = MaterialTheme.typography.bodySmall
                                )

                                if (state.profile?.phone?.isNotBlank() == true) {
                                    Text(
                                        text = state.profile.phone,
                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                        }
                    }
                }
            }

            // Personal Information Section
            item {
                Text(
                    text = "Personal Information",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            item {
                AquaHubGlassCard(shape = AquaHubShapes.cardSecondary) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = {
                                name = it
                                isEditingProfile = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Full Name") },
                            placeholder = { Text("e.g. Maria Santos") },
                            leadingIcon = {
                                Icon(Icons.Outlined.Person, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            singleLine = true,
                            shape = AquaHubShapes.button
                        )

                        OutlinedTextField(
                            value = phone,
                            onValueChange = {
                                phone = it
                                isEditingProfile = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Contact Number (Required for delivery)") },
                            placeholder = { Text("e.g. +63 917 123 4567") },
                            leadingIcon = {
                                Icon(Icons.Outlined.Phone, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            singleLine = true,
                            shape = AquaHubShapes.button
                        )

                        AnimatedVisibility(visible = hasUnsavedChanges) {
                            AquaPrimaryButton(
                                text = "Save Profile Changes",
                                onClick = {
                                    onUpdateProfile(name, phone)
                                    isEditingProfile = false
                                },
                                icon = Icons.Rounded.Check,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Saved Delivery Addresses Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Saved Delivery Addresses",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    TextButton(onClick = { showAddAddressDialog = true }) {
                        Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add New", fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (state.savedAddresses.isEmpty()) {
                item {
                    AquaHubGlassCard(shape = AquaHubShapes.cardSecondary) {
                        Text(
                            text = "No saved addresses yet. Add one for quick checkout!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                itemsIndexed(state.savedAddresses) { index, address ->
                    val isSelected = index == state.selectedAddressIndex
                    AquaHubGlassCard(
                        shape = AquaHubShapes.cardSecondary,
                        borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        onClick = { onSelectAddress(index) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when (address.label.lowercase()) {
                                        "home" -> Icons.Outlined.Home
                                        "work", "office" -> Icons.Outlined.Business
                                        else -> Icons.Outlined.LocationOn
                                    },
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = address.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isSelected) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = AquaHubShapes.chip
                                        ) {
                                            Text(
                                                text = "Default",
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = address.addressLine,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = { onDeleteAddress(index) }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Delete address",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Settings & Support Section
            item {
                Text(
                    text = "Help & Support",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            item {
                AquaHubGlassCard(shape = AquaHubShapes.cardSecondary) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showSupportDialog = true }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "AquaHub Customer Support & FAQ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "How ordering, delivery, and bottle exchanges work",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "App Version",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "AquaHub Customer v1.0.0 (Production Release)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Sign Out Button
            item {
                AquaSecondaryButton(
                    text = "Sign Out",
                    onClick = { showSignOutDialog = true },
                    icon = Icons.AutoMirrored.Rounded.ExitToApp,
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                    contentColor = MaterialTheme.colorScheme.error,
                    borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Add Address Dialog
    if (showAddAddressDialog && !showAddressLocationPicker) {
        AlertDialog(
            onDismissRequest = { showAddAddressDialog = false },
            title = {
                Text(
                    text = "Add Delivery Address",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Address Label",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Home", "Work", "Other").forEach { label ->
                            FilterChip(
                                selected = newAddressLabel == label,
                                onClick = { newAddressLabel = label },
                                label = { Text(label) },
                                shape = AquaHubShapes.chip
                            )
                        }
                    }

                    OutlinedTextField(
                        value = newAddressLine,
                        onValueChange = {},
                        label = { Text("Complete Address") },
                        placeholder = { Text("Search and pin the address") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAddressLocationPicker = true },
                        readOnly = true,
                        maxLines = 3,
                        shape = AquaHubShapes.button,
                        leadingIcon = { Icon(Icons.Outlined.LocationOn, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { showAddressLocationPicker = true }) {
                                Icon(Icons.Outlined.Map, contentDescription = "Search address on map")
                            }
                        },
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val location = newAddressLocation
                        if (newAddressLine.isNotBlank() && location != null) {
                            onAddAddress(
                                DeliveryAddress(
                                    label = newAddressLabel,
                                    addressLine = newAddressLine.trim(),
                                    location = location.point,
                                    placeId = location.placeId,
                                )
                            )
                            newAddressLine = ""
                            newAddressLocation = null
                            showAddAddressDialog = false
                        }
                    },
                    enabled = newAddressLine.isNotBlank() && newAddressLocation != null,
                    shape = AquaHubShapes.button
                ) {
                    Text("Save Address")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAddressDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddressLocationPicker) {
        locationPickerDialog?.invoke(
            newAddressLine.ifBlank { null },
            newAddressLocation?.point,
            { showAddressLocationPicker = false },
            { location ->
                newAddressLocation = location
                newAddressLine = location.formattedAddress
                showAddressLocationPicker = false
            }
        )
    }

    // Support Dialog
    if (showSupportDialog) {
        AlertDialog(
            onDismissRequest = { showSupportDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.WaterDrop, null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "AquaHub Help & FAQ",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "• Refill Orders: The station rider will arrive at your address with clean refilled containers and exchange them with your empty bottles.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Container Ownership: Choose 'Exchange Container' if you have empty standard gallons, or 'Buy New Container' if you need additional containers.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Payment: Currently Cash on Delivery (COD) and Cash on Pickup (COP) are supported.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Need urgent support? Reach out directly to your assigned station from the Order Tracking screen.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSupportDialog = false }) {
                    Text("Got it", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Sign Out Confirmation Dialog
    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = {
                Text(
                    text = "Sign Out?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to sign out of AquaHub Customer?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        onSignOut()
                    }
                ) {
                    Text(
                        text = "Sign Out",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProfileScreenPreview() {
    val sampleState = CustomerUiState(
        profile = CustomerProfile(
            uid = "user-123",
            displayName = "Juan Dela Cruz",
            email = "juan.delacruz@example.com",
            phone = "09171234567"
        ),
        savedAddresses = listOf(
            DeliveryAddress(
                label = "Home",
                addressLine = "123 Katipunan Ave, Quezon City",
                location = GeoPoint(14.65, 121.07),
                placeId = "place-1"
            ),
            DeliveryAddress(
                label = "Office",
                addressLine = "456 Ayala Ave, Makati City",
                location = GeoPoint(14.55, 121.02),
                placeId = "place-2"
            )
        ),
        selectedAddressIndex = 0
    )

    AquaHubCustomerTheme(dynamicColor = false) {
        ProfileContent(
            state = sampleState,
            onUpdateProfile = { _, _ -> },
            onSelectAddress = {},
            onDeleteAddress = {},
            onAddAddress = {},
            onSignOut = {}
        )
    }
}

