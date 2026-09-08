package com.aesprt.aquahub_customer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aesprt.aquahub_customer.ui.components.AquaLoadingStateView
import com.aesprt.aquahub_customer.ui.feature.auth.AuthScreen
import com.aesprt.aquahub_customer.ui.feature.onboarding.OnboardingScreen
import com.aesprt.aquahub_customer.ui.feature.splash.SplashScreen
import com.aesprt.aquahub_customer.ui.navigation.CustomerBottomDestination
import com.aesprt.aquahub_customer.ui.navigation.CustomerDestinations
import com.aesprt.aquahub_customer.ui.navigation.CustomerNavGraph
import com.aesprt.aquahub_customer.ui.theme.*
import org.koin.androidx.compose.koinViewModel

/**
 * Root Composable for the AquaHub Customer Application.
 * Manages the high-level application lifecycle:
 * Splash -> (First-time Onboarding) -> (Authentication) -> Main App Shell (with Bottom Nav).
 */
@Composable
fun CustomerApp(viewModel: CustomerViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var splashCompleted by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AnimatedContent(
            targetState = Triple(splashCompleted, state.sessionReady, state.profile != null),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "appStateTransition"
        ) { (splashDone, ready, signedIn) ->
            when {
                // 1. Animated Splash Screen
                !splashDone -> {
                    SplashScreen(
                        onSplashFinished = { splashCompleted = true }
                    )
                }

                // 2. Loading Session State
                !ready -> {
                    AquaLoadingStateView(
                        message = "Starting AquaHub...",
                        subtitle = "Connecting to water stations"
                    )
                }

                // 3. First Launch Onboarding
                !state.hasCompletedOnboarding && !signedIn -> {
                    OnboardingScreen(
                        onFinished = { viewModel.finishOnboarding() }
                    )
                }

                // 4. Authentication (Google Sign-In)
                !signedIn -> {
                    AuthScreen(
                        firebaseConfigured = state.firebaseConfigured,
                        viewModel = viewModel
                    )
                }

                // 5. Main Customer Application Shell
                else -> {
                    CustomerMainScaffold(
                        state = state,
                        viewModel = viewModel
                    )
                }
            }
        }

        // Global Floating Snackbar
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 80.dp)
        )
    }
}

@Composable
private fun CustomerMainScaffold(
    state: CustomerUiState,
    viewModel: CustomerViewModel
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val isBottomBarVisible = currentRoute in CustomerBottomDestination.roots

    val activeOrdersCount = remember(state.orders) {
        state.orders.count { !it.status.isTerminal }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (isBottomBarVisible) {
                CustomerBottomBar(
                    currentRoute = currentRoute,
                    activeOrdersCount = activeOrdersCount,
                    cartCount = state.cartCount,
                    onNavigate = { route ->
                        if (currentRoute != route) {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        CustomerNavGraph(
            navController = navController,
            state = state,
            viewModel = viewModel,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
private fun CustomerBottomBar(
    currentRoute: String?,
    activeOrdersCount: Int,
    cartCount: Int,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val navShape = RoundedCornerShape(28.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .shadow(
                elevation = 16.dp,
                shape = navShape,
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                ambientColor = Color.Black.copy(alpha = 0.05f)
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.85f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                        Color.White.copy(alpha = 0.30f)
                    )
                ),
                shape = navShape
            ),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shape = navShape,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            CustomerBottomDestination.all.forEach { destination ->
                val selected = currentRoute == destination.route
                val badgeCount = when (destination) {
                    CustomerBottomDestination.Orders -> activeOrdersCount
                    CustomerBottomDestination.Cart -> cartCount
                    else -> 0
                }

                CustomerBottomNavItem(
                    destination = destination,
                    selected = selected,
                    badgeCount = badgeCount,
                    onClick = { onNavigate(destination.route) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CustomerBottomNavItem(
    destination: CustomerBottomDestination,
    selected: Boolean,
    badgeCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.10f else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "navIconScale"
    )

    val iconContainerBg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = tween(220),
        label = "navIconContainerBg"
    )

    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f),
        animationSpec = tween(200),
        label = "navContentColor"
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon with BadgedBox so badges are never clipped by container bounds
        BadgedBox(
            badge = {
                if (badgeCount > 0) {
                    Badge(
                        containerColor = if (destination == CustomerBottomDestination.Orders) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        contentColor = Color.White
                    ) {
                        Text(
                            text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 12.sp
                        )
                    }
                }
            }
        ) {
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(iconContainerBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                    contentDescription = destination.label,
                    tint = contentColor,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        }
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Label
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Active indicator bar
        if (selected) {
            Box(
                modifier = Modifier
                    .size(width = 12.dp, height = 2.5.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                AquaCyan
                            )
                        )
                    )
            )
        } else {
            Spacer(modifier = Modifier.height(2.5.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CustomerBottomBarPreview() {
    AquaHubCustomerTheme(dynamicColor = false) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF6F9FA))
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            CustomerBottomBar(
                currentRoute = CustomerBottomDestination.Home.route,
                activeOrdersCount = 2,
                cartCount = 3,
                onNavigate = {}
            )
        }
    }
}

