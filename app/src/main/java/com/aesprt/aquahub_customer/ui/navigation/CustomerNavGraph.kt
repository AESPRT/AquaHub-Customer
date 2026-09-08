package com.aesprt.aquahub_customer.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.aesprt.aquahub_customer.ui.CustomerUiState
import com.aesprt.aquahub_customer.ui.CustomerViewModel
import com.aesprt.aquahub_customer.ui.components.EmptyStateView
import com.aesprt.aquahub_customer.ui.feature.cart.CartScreen
import com.aesprt.aquahub_customer.ui.feature.checkout.CheckoutScreen
import com.aesprt.aquahub_customer.ui.feature.home.HomeScreen
import com.aesprt.aquahub_customer.ui.feature.orders.OrderTrackingScreen
import com.aesprt.aquahub_customer.ui.feature.orders.OrdersListScreen
import com.aesprt.aquahub_customer.ui.feature.profile.ProfileScreen
import com.aesprt.aquahub_customer.ui.feature.station.StationDetailScreen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong

@Composable
fun CustomerNavGraph(
    navController: NavHostController,
    state: CustomerUiState,
    viewModel: CustomerViewModel,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = CustomerDestinations.Home,
        modifier = modifier,
        enterTransition = { fadeIn(animationSpec = tween(220)) },
        exitTransition = { fadeOut(animationSpec = tween(220)) },
        popEnterTransition = { fadeIn(animationSpec = tween(220)) },
        popExitTransition = { fadeOut(animationSpec = tween(220)) }
    ) {
        // Home Discovery Screen
        composable(CustomerDestinations.Home) {
            HomeScreen(
                state = state,
                viewModel = viewModel,
                onStationClick = { station ->
                    viewModel.selectStation(station)
                    navController.navigate(CustomerDestinations.Station)
                },
                onOrderClick = { orderId ->
                    navController.navigate(CustomerDestinations.orderTrackingRoute(orderId))
                },
                onReorder = { order ->
                    viewModel.reorder(order) {
                        navController.navigate(CustomerDestinations.Cart)
                    }
                }
            )
        }

        // Station Details Screen
        composable(CustomerDestinations.Station) {
            StationDetailScreen(
                state = state,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onViewCart = { navController.navigate(CustomerDestinations.Cart) }
            )
        }

        // Cart Screen
        composable(CustomerDestinations.Cart) {
            CartScreen(
                state = state,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onProceedToCheckout = {
                    viewModel.prepareCheckout()
                    navController.navigate(CustomerDestinations.Checkout)
                },
                onExploreStations = {
                    navController.navigate(CustomerDestinations.Home) {
                        popUpTo(CustomerDestinations.Home) { inclusive = true }
                    }
                }
            )
        }

        // Checkout Screen
        composable(CustomerDestinations.Checkout) {
            CheckoutScreen(
                state = state,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOrderPlaced = { order ->
                    navController.navigate(CustomerDestinations.orderTrackingRoute(order.id)) {
                        popUpTo(CustomerDestinations.Home)
                    }
                }
            )
        }

        // Orders List Screen
        composable(CustomerDestinations.Orders) {
            OrdersListScreen(
                orders = state.orders,
                onOrderClick = { orderId ->
                    navController.navigate(CustomerDestinations.orderTrackingRoute(orderId))
                },
                onReorder = { order ->
                    viewModel.reorder(order) {
                        navController.navigate(CustomerDestinations.Cart)
                    }
                },
                onBrowseStations = {
                    navController.navigate(CustomerDestinations.Home) {
                        popUpTo(CustomerDestinations.Home) { inclusive = true }
                    }
                }
            )
        }

        // Live Order Tracking Screen
        composable(
            route = CustomerDestinations.OrderTracking,
            arguments = listOf(navArgument("orderId") { type = NavType.StringType })
        ) { backStackEntry ->
            val orderId = backStackEntry.arguments?.getString("orderId").orEmpty()
            val order = state.orders.firstOrNull { it.id == orderId }

            if (order != null) {
                OrderTrackingScreen(
                    order = order,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            } else {
                EmptyStateView(
                    title = "Order Not Found",
                    description = "Syncing order details with the station. Please wait or check your active orders.",
                    icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                    actionLabel = "Back to Orders",
                    onAction = { navController.popBackStack() }
                )
            }
        }

        // Profile & Settings Screen
        composable(CustomerDestinations.Profile) {
            ProfileScreen(
                state = state,
                viewModel = viewModel
            )
        }
    }
}
