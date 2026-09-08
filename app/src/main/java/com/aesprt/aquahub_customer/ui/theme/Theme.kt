package com.aesprt.aquahub_customer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AquaPrimaryLight,
    onPrimary = NavyDark,
    primaryContainer = NavyDeep,
    onPrimaryContainer = AquaCyan,
    secondary = AquaCyan,
    onSecondary = NavyDark,
    secondaryContainer = Color(0xFF003F47),
    onSecondaryContainer = AquaCyan,
    tertiary = AquaCyan,
    onTertiary = NavyDark,
    background = BackgroundDark,
    surface = SurfaceDark,
    onBackground = White,
    onSurface = White,
    surfaceVariant = SurfaceContainerDark,
    onSurfaceVariant = Slate300,
    outline = Slate600,
    outlineVariant = Slate700,
    error = ErrorRed,
    onError = White,
    errorContainer = Color(0xFF6D2026),
    onErrorContainer = Color(0xFFFFDAD9),
)

private val LightColorScheme = lightColorScheme(
    primary = AquaPrimary,
    onPrimary = White,
    primaryContainer = LightAqua,
    onPrimaryContainer = NavyDeep,
    secondary = AquaCyan,
    onSecondary = White,
    secondaryContainer = Color(0xFFE0F7FA),
    onSecondaryContainer = NavyDeep,
    tertiary = NavyDeep,
    onTertiary = White,
    tertiaryContainer = LightAqua,
    onTertiaryContainer = NavyDeep,
    error = ErrorRed,
    onError = White,
    errorContainer = ErrorRedLight,
    onErrorContainer = ErrorRed,
    background = AquaBackground,
    surface = AquaSurface,
    onBackground = Slate900,
    onSurface = Slate900,
    onSurfaceVariant = Slate600,
    surfaceVariant = Slate100,
    outline = Slate300,
    outlineVariant = Slate200,
)

@Composable
fun AquaHubCustomerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
