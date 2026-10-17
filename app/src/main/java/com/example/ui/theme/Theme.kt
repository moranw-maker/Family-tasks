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

private val PureWhiteColorScheme = lightColorScheme(
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
    background = Color(0xFFF8FAFD),
    surface = Color.White,
    surfaceVariant = Color(0xFFF1F5F9),
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    outline = OutlineLight
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    themeColor: AppThemeColor = AppThemeColor.GOOGLE_BLUE,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val primaryColor = Color(themeColor.primaryHex)
    val primaryContainer = Color(themeColor.containerHex)

    val colorScheme = PureWhiteColorScheme.copy(
        primary = primaryColor,
        primaryContainer = primaryContainer,
        onPrimaryContainer = primaryColor
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
