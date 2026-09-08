package com.aesprt.aquahub_customer.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object AquaHubSpacing {
    val none: Dp = 0.dp
    val extraSmall: Dp = 4.dp
    val small: Dp = 8.dp
    val mediumSmall: Dp = 12.dp
    val medium: Dp = 16.dp
    val mediumLarge: Dp = 20.dp
    val large: Dp = 24.dp
    val extraLarge: Dp = 32.dp
    val huge: Dp = 48.dp
}

object AquaHubShapes {
    val chip = RoundedCornerShape(10.dp)
    val pill = RoundedCornerShape(50)
    val button = RoundedCornerShape(16.dp)
    val buttonLarge = RoundedCornerShape(18.dp)
    val inputField = RoundedCornerShape(16.dp)
    val cardSecondary = RoundedCornerShape(16.dp)
    val cardPrimary = RoundedCornerShape(20.dp)
    val cardLarge = RoundedCornerShape(24.dp)
    val dialog = RoundedCornerShape(28.dp)
    val bottomSheet = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    val floatingBar = RoundedCornerShape(28.dp)
    val segmentedContainer = RoundedCornerShape(16.dp)
}

object AquaHubIconSize {
    val small: Dp = 18.dp
    val standard: Dp = 22.dp
    val medium: Dp = 26.dp
    val large: Dp = 32.dp
    val extraLarge: Dp = 48.dp
}
