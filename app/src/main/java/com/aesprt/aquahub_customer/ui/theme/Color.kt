package com.aesprt.aquahub_customer.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

// AquaHub Primary Brand Palette
val AquaPrimary = Color(0xFF1264E6)
val AquaPrimaryDark = Color(0xFF073B8C)
val AquaCyan = Color(0xFF16C7E8)
val AquaCyanSoft = Color(0xFF81E6F2)
val NavyDeep = Color(0xFF073B8C)
val LightAqua = Color(0xFFDFF8FC)
val AquaBackground = Color(0xFFF4F9FF)
val AquaSurface = Color(0xFFFFFFFF)

// Semantic Status Colors
val SuccessGreen = Color(0xFF20B26B)
val SuccessGreenLight = Color(0xFFE8F8F0)
val WarningAmber = Color(0xFFF4A623)
val WarningAmberLight = Color(0xFFFEF6E9)
val ErrorRed = Color(0xFFE5484D)
val ErrorRedLight = Color(0xFFFDE8E9)

// Neutral Palette
val Slate900 = Color(0xFF0F172A)
val Slate800 = Color(0xFF1E293B)
val Slate700 = Color(0xFF334155)
val Slate600 = Color(0xFF475569)
val Slate500 = Color(0xFF64748B)
val Slate400 = Color(0xFF94A3B8)
val Slate300 = Color(0xFFCBD5E1)
val Slate200 = Color(0xFFE2E8F0)
val Slate100 = Color(0xFFF1F5F9)
val Slate50 = Color(0xFFF8FAFC)
val White = Color(0xFFFFFFFF)

// Dark Theme Palette
val BackgroundDark = Color(0xFF0B132B)
val SurfaceDark = Color(0xFF1C2541)
val SurfaceContainerDark = Color(0xFF243054)
val NavyDark = Color(0xFF071426)
val AquaPrimaryLight = Color(0xFF3B82F6)

// Glassmorphism & Translucent Accents
val GlassWhite = Color(0xEBFFFFFF)
val GlassWhiteSubtle = Color(0xB8FFFFFF)
val GlassBorder = Color(0x66FFFFFF)
val AquaBorder = Color(0x261264E6)
val GlassBorderAqua = Color(0x3316C7E8)
val GlassDark = Color(0xCC1C2541)
val GlassBorderDark = Color(0x333B82F6)

// AquaHub Expressive Accents
val AquaTeal = Color(0xFF0EA5E9)
val AquaIce = Color(0xFFF0F9FF)
val AquaSurfaceLight = Color(0xF7FFFFFF)
val AquaSurfaceContainer = Color(0xFFF0F5FA)

// Shimmer Skeleton Colors
val ShimmerBaseLight = Color(0xFFE2E8F0)
val ShimmerHighlightLight = Color(0xFFF8FAFC)
val ShimmerBaseDark = Color(0xFF1E293B)
val ShimmerHighlightDark = Color(0xFF334155)

// Gradients
val AquaHeroGradient = Brush.linearGradient(listOf(AquaPrimary, AquaCyan))
val AquaSoftGradient = Brush.linearGradient(listOf(Color(0xFFF4FBFF), LightAqua))
val AquaGlassGradient = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xEEF0F9FF)))
val WaterDropletGradient = Brush.verticalGradient(listOf(AquaCyan, AquaPrimary))

@Composable
fun aquaHeroGradient(): Brush = Brush.linearGradient(
    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
)

@Composable
fun aquaSoftGradient(): Brush = Brush.linearGradient(
    listOf(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
    ),
)

// Order Status Chip Colors
val StatusPending = Color(0xFFD97706)
val StatusPendingBg = Color(0xFFFEF3C7)

val StatusAccepted = Color(0xFF2563EB)
val StatusAcceptedBg = Color(0xFFDBEAFE)

val StatusPreparing = Color(0xFF0284C7)
val StatusPreparingBg = Color(0xFFE0F2FE)

val StatusReady = Color(0xFF059669)
val StatusReadyBg = Color(0xFFD1FAE5)

val StatusOutForDelivery = Color(0xFF7C3AED)
val StatusOutForDeliveryBg = Color(0xFFEDE9FE)

val StatusDelivered = Color(0xFF0D9488)
val StatusDeliveredBg = Color(0xFFCCFBF1)

val StatusCompleted = Color(0xFF16A34A)
val StatusCompletedBg = Color(0xFFDCFCE7)

val StatusRejected = Color(0xFFDC2626)
val StatusRejectedBg = Color(0xFFFEE2E2)

val StatusCancelled = Color(0xFF6B7280)
val StatusCancelledBg = Color(0xFFF3F4F6)

// Aliases for compatibility
val Success = SuccessGreen
val Warning = WarningAmber
val Danger = ErrorRed
