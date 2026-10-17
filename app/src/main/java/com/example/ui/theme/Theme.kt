package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.model.AppThemeColor

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF002D6C),
    primaryContainer = GoogleBlueDark,
    onPrimaryContainer = Color(0xFFD2E3FC),
    secondary = Color(0xFF81C995),
    onSecondary = Color(0xFF0A3818),
    secondaryContainer = GoogleGreenDark,
    onSecondaryContainer = Color(0xFFCEEAD6),
    tertiary = Color(0xFFFDD663),
    onTertiary = Color(0xFF3E2C00),
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark,
    outline = OutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = GoogleBlue,
    onPrimary = Color.White,
    primaryContainer = GoogleBlueLight,
    onPrimaryContainer = GoogleBlueDark,
    secondary = GoogleGreen,
    onSecondary = Color.White,
    secondaryContainer = GoogleGreenLight,
    onSecondaryContainer = GoogleGreenDark,
    tertiary = GoogleYellowDark,
    onTertiary = Color.White,
    background = BackgroundLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    outline = OutlineLight
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeColor: AppThemeColor = AppThemeColor.GOOGLE_BLUE,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val primaryColor = Color(themeColor.primaryHex)
    val primaryContainer = Color(themeColor.containerHex)

    val colorScheme = if (darkTheme) {
        DarkColorScheme.copy(
            primary = primaryColor,
            primaryContainer = primaryColor.copy(alpha = 0.3f)
        )
    } else {
        LightColorScheme.copy(
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
