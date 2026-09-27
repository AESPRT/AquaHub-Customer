package com.aesprt.aquahub_customer.ui.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub_customer.R
import com.aesprt.aquahub_customer.domain.CustomerProfile
import com.aesprt.aquahub_customer.domain.DeliveryAddress
import com.aesprt.aquahub_customer.domain.GeoPoint
import com.aesprt.aquahub_customer.ui.CustomerUiState
import com.aesprt.aquahub_customer.ui.CustomerViewModel
import com.aesprt.aquahub_customer.ui.components.*
import com.aesprt.aquahub_customer.ui.theme.AquaHubCustomerTheme

@Composable
fun CompleteProfileScreen(state: CustomerUiState, viewModel: CustomerViewModel) {
    var showLocationPicker by remember { mutableStateOf(false) }

    if (showLocationPicker) {
        CustomerLocationPickerDialog(
            state = state,
            viewModel = viewModel,
            title = "Choose delivery address",
            initialAddress = state.address.ifBlank { state.profile?.address?.addressLine },
            initialLocation = state.location ?: state.profile?.address?.location,
            onDismiss = { showLocationPicker = false },
            onConfirm = {
                viewModel.setDeliveryLocation(it)
                showLocationPicker = false
            }
        )
    }

    CompleteProfileContent(
        state = state,
        onChooseAddress = { showLocationPicker = true },
        onSave = { name, phone ->
            val point = state.location ?: state.profile?.address?.location
            val address = state.address.ifBlank { state.profile?.address?.addressLine.orEmpty() }
            if (point == null || address.isBlank()) {
                viewModel.showMessage("Select a delivery address on the map.")
            } else {
                viewModel.completeProfile(
                    name,
                    phone,
                    DeliveryAddress("Primary", address, point, state.addressPlaceId),
                )
            }
        },
    )
}

@Composable
private fun CompleteProfileContent(
    state: CustomerUiState,
    onChooseAddress: () -> Unit,
    onSave: (name: String, phone: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(state.profile?.displayName) { mutableStateOf(state.profile?.displayName.orEmpty()) }
    var phone by remember(state.profile?.phone) { mutableStateOf(state.profile?.phone.orEmpty()) }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(112.dp)
                    .shadow(
                        elevation = 12.dp,
                        shape = CircleShape,
                        ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = .28f),
                        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = .28f),
                    )
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = .34f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = .16f),
                            ),
                        ),
                        shape = CircleShape,
                    )
                    .padding(12.dp),
            ) {
                AquaHubLogo(
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = stringResource(R.string.app_name),
                )
            }
        }
        Text(
            "Complete your profile",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "We need your delivery details before you can place an order.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        AquaHubGlassCard(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                name,
                { name = it },
                Modifier.fillMaxWidth(),
                label = { Text("Full name") },
                singleLine = true
            )
            OutlinedTextField(
                phone,
                { phone = it },
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                label = { Text("Phone number") },
                singleLine = true
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                OutlinedTextField(
                    value = state.address.ifBlank {
                        state.profile?.address?.addressLine.orEmpty()
                    },
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    label = { Text("Delivery address") },
                    placeholder = { Text("Tap to select your location") },
                    leadingIcon = {
                        Icon(Icons.Outlined.LocationOn, contentDescription = null)
                    },
                    trailingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = "Select delivery location")
                    },
                    minLines = 2,
                    maxLines = 3,
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(onClick = onChooseAddress, role = Role.Button),
                )
            }
            state.authError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            AquaPrimaryButton(
                text = "Save and continue",
                onClick = { onSave(name, phone) },
                loading = state.authLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun CompleteProfileScreenPreview() {
    val previewPoint = GeoPoint(14.6760, 121.0437)
    val previewAddress = DeliveryAddress(
        label = "Home",
        addressLine = "05 Narra Street, Payatas B, Quezon City",
        location = previewPoint,
    )
    AquaHubCustomerTheme(dynamicColor = false) {
        CompleteProfileContent(
            state = CustomerUiState(
                profile = CustomerProfile(
                    uid = "preview-user",
                    displayName = "Maria Santos",
                    email = "maria@example.com",
                    phone = "0917 123 4567",
                    address = previewAddress,
                ),
                address = previewAddress.addressLine,
                location = previewPoint,
            ),
            onChooseAddress = {},
            onSave = { _, _ -> },
        )
    }
}
