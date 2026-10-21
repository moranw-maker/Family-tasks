package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private fun TextUnit.scaleBy(factor: Float): TextUnit =
    if (this == TextUnit.Unspecified) this else (this.value * factor).sp

private fun TextStyle.scaleBy(factor: Float): TextStyle =
    copy(
        fontSize = fontSize.scaleBy(factor),
        lineHeight = lineHeight.scaleBy(factor)
    )

val BaseTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 56.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        lineHeight = 46.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.25.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    )
)

val Typography: Typography = BaseTypography

/**
 * Scales the application's Material 3 [Typography] styles proportionally by [scaleFactor]
 * without overriding or double-multiplying the Android system fontScale.
 */
fun scaledTypography(scaleFactor: Float): Typography {
    if (scaleFactor == 1.0f) return BaseTypography
    val clamped = scaleFactor.coerceIn(0.85f, 1.45f)
    return Typography(
        displayLarge = BaseTypography.displayLarge.scaleBy(clamped),
        displayMedium = BaseTypography.displayMedium.scaleBy(clamped),
        displaySmall = BaseTypography.displaySmall.scaleBy(clamped),
        headlineLarge = BaseTypography.headlineLarge.scaleBy(clamped),
        headlineMedium = BaseTypography.headlineMedium.scaleBy(clamped),
        headlineSmall = BaseTypography.headlineSmall.scaleBy(clamped),
        titleLarge = BaseTypography.titleLarge.scaleBy(clamped),
        titleMedium = BaseTypography.titleMedium.scaleBy(clamped),
        titleSmall = BaseTypography.titleSmall.scaleBy(clamped),
        bodyLarge = BaseTypography.bodyLarge.scaleBy(clamped),
        bodyMedium = BaseTypography.bodyMedium.scaleBy(clamped),
        bodySmall = BaseTypography.bodySmall.scaleBy(clamped),
        labelLarge = BaseTypography.labelLarge.scaleBy(clamped),
        labelMedium = BaseTypography.labelMedium.scaleBy(clamped),
        labelSmall = BaseTypography.labelSmall.scaleBy(clamped)
    )
}
