package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.model.AppThemeColor
import com.example.model.AppThemeMode

private val SoftTactileLightColorScheme = lightColorScheme(
    primary = GoogleBlue,
    onPrimary = Color.White,
    primaryContainer = GoogleBlueLight,
    onPrimaryContainer = GoogleBlueDark,
    secondary = SoftTeal,
    onSecondary = Color.White,
    secondaryContainer = SoftTealLight,
    onSecondaryContainer = SoftTealDark,
    tertiary = SoftLavender,
    onTertiary = Color.White,
    tertiaryContainer = SoftLavenderLight,
    onTertiaryContainer = SoftLavenderDark,
    background = BackgroundLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = OutlineLight
)

private val SoftTactileDarkColorScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF4FD1C5),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF234E52),
    onSecondaryContainer = Color(0xFFE6FFFA),
    tertiary = Color(0xFFB794F4),
    onTertiary = Color(0xFF0F172A),
    tertiaryContainer = Color(0xFF44337A),
    onTertiaryContainer = Color(0xFFF3EBFF),
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = OutlineDark
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: AppThemeMode? = null,
    themeColor: AppThemeColor = AppThemeColor.GOOGLE_BLUE,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        null -> darkTheme
    }

    val colorScheme = if (useDark) {
        SoftTactileDarkColorScheme
    } else {
        val primaryColor = Color(themeColor.primaryHex)
        val primaryContainer = Color(themeColor.containerHex)
        SoftTactileLightColorScheme.copy(
            primary = primaryColor,
            primaryContainer = primaryContainer,
            onPrimaryContainer = primaryColor
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
