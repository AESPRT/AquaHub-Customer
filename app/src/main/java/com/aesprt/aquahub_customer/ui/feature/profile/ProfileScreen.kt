package com.aesprt.aquahub_customer.ui.feature.profile

import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import com.aesprt.aquahub_customer.data.AquaHubLinks
import com.aesprt.aquahub_customer.domain.*
import com.aesprt.aquahub_customer.ui.CustomerUiState
import com.aesprt.aquahub_customer.ui.CustomerViewModel
import com.aesprt.aquahub_customer.ui.components.AquaCustomerTopBar
import com.aesprt.aquahub_customer.ui.components.AquaHubGlassCard
import com.aesprt.aquahub_customer.ui.components.AquaPrimaryButton
import com.aesprt.aquahub_customer.ui.components.AquaSecondaryButton
import com.aesprt.aquahub_customer.ui.components.CustomerLocationPickerDialog
import com.aesprt.aquahub_customer.ui.feature.station.StationQrScannerActivity
import com.aesprt.aquahub_customer.ui.theme.*
import com.aesprt.aquahub_customer.data.preferences.ThemeMode
import com.aesprt.aquahub_customer.data.preferences.ThemePreferences
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun ProfileScreen(
    state: CustomerUiState,
    viewModel: CustomerViewModel,
    modifier: Modifier = Modifier
) {
    ProfileContent(
        themePreferences = koinInject(),
        state = state,
        onUpdateProfile = { name, phone -> viewModel.updateProfile(name, phone) },
        onSelectAddress = { index -> viewModel.selectSavedAddress(index) },
        onDeleteAddress = { index -> viewModel.deleteSavedAddress(index) },
        onAddAddress = { address -> viewModel.addSavedAddress(address) },
        onSignOut = { viewModel.signOut() },
        deletingAccount = state.deletingAccount,
        onDeleteAccount = { viewModel.deleteAccount() },
        accountError = state.authError,
        onDismissAccountError = { viewModel.clearAuthError() },
        onReauthenticateEmail = { email, password -> viewModel.reauthenticateEmail(email, password) { viewModel.deleteAccount() } },
        onBeginGoogleReauthentication = { viewModel.reauthenticateGoogle { viewModel.deleteAccount() } },
        onScanStationQrResult = viewModel::handleIncomingLink,
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
    themePreferences: ThemePreferences,
    deletingAccount: Boolean = false,
    onDeleteAccount: () -> Unit = {},
    accountError: String? = null,
    onDismissAccountError: () -> Unit = {},
    onReauthenticateEmail: (String, String) -> Unit = { _, _ -> },
    onBeginGoogleReauthentication: () -> Unit = {},
    onScanStationQrResult: (String) -> Unit = {},
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
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showLegalDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showFinalDeleteDialog by remember { mutableStateOf(false) }
    var showAccountErrorDialog by remember(accountError) { mutableStateOf(accountError != null) }
    var showReauthDialog by remember { mutableStateOf(false) }
    var reauthEmail by remember(state.profile?.email) { mutableStateOf(state.profile?.email.orEmpty()) }
    var reauthPassword by remember { mutableStateOf("") }
    val themeMode by themePreferences.mode.collectAsState(initial = ThemeMode.SYSTEM)
    val themeScope = rememberCoroutineScope()
    val context = LocalContext.current
    val stationQrScannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.getStringExtra(StationQrScannerActivity.EXTRA_STATION_LINK)
                ?.let(onScanStationQrResult)
        }
    }

    fun openExternalUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            showSupportDialog = true
        }
    }

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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.AddLocationAlt, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Add your first address", fontWeight = FontWeight.Bold)
                                Text(
                                    "Save a precise map pin for faster, more reliable delivery.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FilledIconButton(onClick = { showAddAddressDialog = true }) {
                                Icon(Icons.Outlined.Add, contentDescription = "Add delivery address")
                            }
                        }
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

            // Appearance
            item {
                Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
            item {
                AquaHubGlassCard(shape = AquaHubShapes.cardSecondary) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.padding(start = 12.dp)) {
                                Text("App theme", fontWeight = FontWeight.Bold)
                                Text(
                                    "Choose the display that feels best to you",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ThemeMode.entries.forEach { option ->
                                ThemeModeButton(
                                    option = option,
                                    selected = themeMode == option,
                                    onClick = { themeScope.launch { themePreferences.setMode(option) } },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            // Preferences, privacy & support
            item {
                Text(
                    text = "Preferences & Privacy",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            item {
                AquaHubGlassCard(shape = AquaHubShapes.cardSecondary) {
                    ProfileSettingRow(
                        icon = Icons.Outlined.QrCodeScanner,
                        title = "Scan station QR",
                        subtitle = if (state.isStationOwnedCustomer) {
                            "Switch your preferred station by scanning its QR code"
                        } else {
                            "Link this account to your preferred water station"
                        },
                        onClick = {
                            stationQrScannerLauncher.launch(
                                Intent(context, StationQrScannerActivity::class.java)
                            )
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    ProfileSettingRow(
                        icon = Icons.Outlined.NotificationsNone,
                        title = "Notifications",
                        subtitle = "Manage order updates in Android settings",
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                                        AndroidSettings.EXTRA_APP_PACKAGE,
                                        context.packageName
                                    )
                                )
                            }.onFailure { showSupportDialog = true }
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    ProfileSettingRow(
                        icon = Icons.Outlined.Security,
                        title = "Privacy & Security",
                        subtitle = "Account data, sign-in, and deletion",
                        onClick = { showPrivacyDialog = true }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    ProfileSettingRow(
                        icon = Icons.Outlined.Description,
                        title = "Legal",
                        subtitle = "Privacy Policy, Terms, and Support",
                        onClick = { showLegalDialog = true }
                    )
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

            // Delete Account
            item {
                AquaSecondaryButton(
                    text = "Delete Account",
                    onClick = { showDeleteDialog = true },
                    icon = Icons.Outlined.DeleteForever,
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.20f),
                    contentColor = MaterialTheme.colorScheme.error,
                    borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                )
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

                    Box(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = newAddressLine,
                            onValueChange = {},
                            label = { Text("Complete Address") },
                            placeholder = { Text("Search and pin the address") },
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = true,
                            maxLines = 3,
                            shape = AquaHubShapes.button,
                            leadingIcon = { Icon(Icons.Outlined.LocationOn, contentDescription = null) },
                            trailingIcon = {
                                Icon(Icons.Outlined.Map, contentDescription = "Search address on map")
                            },
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable(
                                    onClick = { showAddressLocationPicker = true },
                                    role = Role.Button,
                                ),
                        )
                    }
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

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("Privacy & Security", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "AquaHub uses your account, phone number, and delivery location to authenticate you, calculate delivery eligibility, and fulfill orders. Your private profile and device token are removed when account deletion succeeds. Station transaction history may be retained with personal details anonymized."
                )
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text("Got it") }
            }
        )
    }

    if (showLegalDialog) {
        AlertDialog(
            onDismissRequest = { showLegalDialog = false },
            title = { Text("AquaHub Legal", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Open the official documents for this build.", style = MaterialTheme.typography.bodyMedium)
                    AquaHubLinks.privacyPolicy?.let { url ->
                        TextButton(onClick = { showLegalDialog = false; openExternalUrl(url) }) {
                            Text("Privacy Policy")
                        }
                    }
                    AquaHubLinks.terms?.let { url ->
                        TextButton(onClick = { showLegalDialog = false; openExternalUrl(url) }) {
                            Text("Terms of Service")
                        }
                    }
                    AquaHubLinks.support?.let { url ->
                        TextButton(onClick = { showLegalDialog = false; openExternalUrl(url) }) {
                            Text("Support")
                        }
                    }
                    if (AquaHubLinks.privacyPolicy == null && AquaHubLinks.terms == null && AquaHubLinks.support == null) {
                        Text(
                            "Official legal links are not configured for this build. Please contact AquaHub support.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLegalDialog = false }) { Text("Close") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete your AquaHub account?") },
            text = { Text("Your personal profile, saved addresses, device tokens, and private account data will be permanently removed. Completed station order history will be retained with personal details anonymized. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { showDeleteDialog = false; showFinalDeleteDialog = true }) { Text("Continue", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }
    if (showFinalDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!deletingAccount) showFinalDeleteDialog = false },
            title = { Text("Permanently delete account?") },
            text = { Text("This final confirmation deletes your AquaHub identity and cloud data. You will be signed out when deletion succeeds.") },
            confirmButton = { TextButton(enabled = !deletingAccount, onClick = { onDeleteAccount() }) { Text(if (deletingAccount) "Deleting…" else "Delete permanently", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(enabled = !deletingAccount, onClick = { showFinalDeleteDialog = false }) { Text("Cancel") } }
        )
    }

    if (showAccountErrorDialog && accountError != null) {
        AlertDialog(
            onDismissRequest = { showAccountErrorDialog = false; onDismissAccountError() },
            title = { Text("Account deletion failed", fontWeight = FontWeight.Bold) },
            text = { Text(accountError, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    showAccountErrorDialog = false
                    onDismissAccountError()
                    if (accountError.contains("sign in again", ignoreCase = true)) showReauthDialog = true else showFinalDeleteDialog = true
                }) {
                    Text("Try again", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { showAccountErrorDialog = false; onDismissAccountError() }) { Text("Close") } }
        )
    }

    if (showReauthDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.authLoading) showReauthDialog = false },
            title = { Text("Verify your identity") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("For your security, sign in again before deleting your account.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(reauthEmail, { reauthEmail = it }, label = { Text("Email") }, leadingIcon = { Icon(Icons.Outlined.Email, null) }, singleLine = true)
                    OutlinedTextField(reauthPassword, { reauthPassword = it }, label = { Text("Password") }, leadingIcon = { Icon(Icons.Outlined.Lock, null) }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = !state.authLoading, onClick = { showReauthDialog = false; onReauthenticateEmail(reauthEmail, reauthPassword) }) {
                    Text(if (state.authLoading) "Checking…" else "Verify and continue")
                }
            },
            dismissButton = {
                Row {
                    TextButton(enabled = !state.authLoading, onClick = { showReauthDialog = false }) { Text("Cancel") }
                    TextButton(enabled = !state.authLoading, onClick = { showReauthDialog = false; onBeginGoogleReauthentication() }) { Text("Use Google") }
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

@Composable
private fun ThemeModeButton(
    option: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = when (option) {
        ThemeMode.SYSTEM -> Icons.Outlined.SettingsBrightness
        ThemeMode.LIGHT -> Icons.Outlined.LightMode
        ThemeMode.DARK -> Icons.Outlined.DarkMode
    }
    val label = option.name.lowercase().replaceFirstChar(Char::uppercase)
    Surface(
        modifier = modifier
            .height(76.dp)
            .clickable(role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(5.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProfileSettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(name = "Light Mode", showBackground = true)
@Preview(name = "Dark Mode", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
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
        themePreferences = koinInject(),
            state = sampleState,
            onUpdateProfile = { _, _ -> },
            onSelectAddress = {},
            onDeleteAddress = {},
            onAddAddress = {},
            onSignOut = {}
        )
    }
}
