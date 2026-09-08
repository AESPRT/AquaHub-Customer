package com.aesprt.aquahub_customer.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * AquaHub Motion System:
 * Centralized easing, durations, and spring specifications to ensure a fluid,
 * water-like responsive feel across cards, buttons, dialogs, and navigation.
 */
object AquaMotion {
    // Standard Durations (ms)
    const val DurationFast = 150
    const val DurationMedium = 280
    const val DurationNormal = 360
    const val DurationSlow = 500
    const val DurationWaterFlow = 850

    // Water Easings
    val WaterDropEasing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
    val SmoothExitEasing = FastOutSlowInEasing
    val SmoothEnterEasing = LinearOutSlowInEasing

    // Spring Specifications
    val springResponsive = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    val springSubtle = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    val springPress = spring<Float>(
        dampingRatio = 0.7f,
        stiffness = Spring.StiffnessLow
    )

    // Standard Tweens
    fun <T> waterTween(duration: Int = DurationMedium) = tween<T>(
        durationMillis = duration,
        easing = WaterDropEasing
    )
}
