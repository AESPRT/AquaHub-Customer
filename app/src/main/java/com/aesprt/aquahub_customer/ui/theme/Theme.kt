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
import com.aesprt.aquahub_customer.data.preferences.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = AquaPrimaryDark,
    onPrimary = White,
    primaryContainer = NavyDeep,
    onPrimaryContainer = AquaCyan,
    secondary = AquaCyan,
    onSecondary = NavyDark,
    secondaryContainer = Color(0xFF003F47),
    onSecondaryContainer = AquaCyanSoft,
    tertiary = AquaCyan,
    onTertiary = NavyDark,
    tertiaryContainer = Color(0xFF003F47),
    onTertiaryContainer = AquaCyanSoft,
    background = BackgroundDark,
    surface = SurfaceDark,
    onBackground = White,
    onSurface = White,
    surfaceVariant = SurfaceContainerDark,
    onSurfaceVariant = Slate300,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceContainerDark,
    surfaceContainerLowest = NavyDark,
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
    surfaceVariant = Slate50,
    surfaceContainer = White,
    surfaceContainerHigh = Color(0xFFF0F6FE),
    surfaceContainerLowest = White,
    outline = Slate300,
    outlineVariant = Slate200,
    inverseSurface = Slate800,
    inverseOnSurface = White,
    inversePrimary = AquaPrimaryDark,
)

@Composable
fun AquaHubCustomerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val resolvedDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> darkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (resolvedDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        resolvedDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
