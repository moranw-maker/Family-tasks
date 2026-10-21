package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.model.AppTextScale
import com.example.model.AppThemeColor
import com.example.model.AppThemeMode

private val SoftGlassLightColorScheme = lightColorScheme(
    primary = GoogleBlue,
    onPrimary = Color.White,
    primaryContainer = GoogleBlueContainer,
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
    outline = OutlineLight,
    outlineVariant = Color(0xFFCBD5E1)
)

private val SoftGlassDarkColorScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF0B1120),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF0B1120),
    secondaryContainer = Color(0xFF134E4A),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFFA78BFA),
    onTertiary = Color(0xFF0B1120),
    tertiaryContainer = Color(0xFF4C1D95),
    onTertiaryContainer = Color(0xFFEDE9FE),
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    onSurfaceVariant = TextSecondaryDark,
    outline = OutlineDark,
    outlineVariant = Color(0xFF475569)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: AppThemeMode? = null,
    textScale: AppTextScale = AppTextScale.NORMAL,
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
        SoftGlassDarkColorScheme
    } else {
        val primaryColor = Color(themeColor.primaryHex)
        val primaryContainer = Color(themeColor.containerHex)
        SoftGlassLightColorScheme.copy(
            primary = primaryColor,
            primaryContainer = primaryContainer,
            onPrimaryContainer = primaryColor
        )
    }

    val softGlassColors = if (useDark) DarkSoftGlassColors else LightSoftGlassColors
    val activeTypography = remember(textScale) {
        scaledTypography(textScale.scaleFactor)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !useDark
                insetsController.isAppearanceLightNavigationBars = !useDark
            }
        }
    }

    CompositionLocalProvider(LocalSoftGlassColors provides softGlassColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = activeTypography,
            content = content
        )
    }
}
