package com.aesprt.aquahub_customer.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.ui.graphics.vector.ImageVector

object CustomerDestinations {
    const val Splash = "splash"
    const val Onboarding = "onboarding"
    const val Auth = "auth"
    const val Home = "home"
    const val Orders = "orders"
    const val Cart = "cart"
    const val Profile = "profile"
    const val Station = "station"
    const val Checkout = "checkout"
    const val OrderTracking = "order_tracking/{orderId}"

    fun orderTrackingRoute(orderId: String) = "order_tracking/$orderId"
}

sealed class CustomerBottomDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Home : CustomerBottomDestination(
        route = CustomerDestinations.Home,
        label = "Home",
        selectedIcon = Icons.Rounded.Home,
        unselectedIcon = Icons.Outlined.Home
    )

    data object Orders : CustomerBottomDestination(
        route = CustomerDestinations.Orders,
        label = "Orders",
        selectedIcon = Icons.AutoMirrored.Rounded.ReceiptLong,
        unselectedIcon = Icons.AutoMirrored.Outlined.ReceiptLong
    )

    data object Cart : CustomerBottomDestination(
        route = CustomerDestinations.Cart,
        label = "Cart",
        selectedIcon = Icons.Rounded.ShoppingCart,
        unselectedIcon = Icons.Outlined.ShoppingCart
    )

    data object Profile : CustomerBottomDestination(
        route = CustomerDestinations.Profile,
        label = "Account",
        selectedIcon = Icons.Rounded.Person,
        unselectedIcon = Icons.Outlined.Person
    )

    companion object {
        val all = listOf(Home, Orders, Cart, Profile)
        val roots = setOf(CustomerDestinations.Home, CustomerDestinations.Orders, CustomerDestinations.Cart, CustomerDestinations.Profile)
    }
}
