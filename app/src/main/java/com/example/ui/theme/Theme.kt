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
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF6D28D9),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2EBFF),
    onSecondaryContainer = Color(0xFF4C1D95),
    tertiary = SoftTeal,
    onTertiary = Color.White,
    tertiaryContainer = SoftTealLight,
    onTertiaryContainer = SoftTealDark,
    background = Color(0xFFF5F7FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F5FE),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF334155),
    outline = Color(0xFFD6E0F0),
    outlineVariant = Color(0xFFCBD5E1)
)

private val SoftGlassDarkColorScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF0B1120),
    primaryContainer = Color(0xFF1E3563),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFFA78BFA),
    onSecondary = Color(0xFF0B1120),
    secondaryContainer = Color(0xFF261E47),
    onSecondaryContainer = Color(0xFFEDE9FE),
    tertiary = Color(0xFF2DD4BF),
    onTertiary = Color(0xFF0B1120),
    tertiaryContainer = Color(0xFF134E4A),
    onTertiaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF0B1120),
    surface = Color(0xFF151F32),
    surfaceVariant = Color(0xFF1E293B),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF2D3C56),
    outlineVariant = Color(0xFF475569)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    themeMode: AppThemeMode? = AppThemeMode.LIGHT,
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
