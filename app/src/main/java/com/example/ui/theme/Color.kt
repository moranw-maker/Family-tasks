package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Soft Glass & Material 3 Palette (Light & Dark)
val GoogleBlue = Color(0xFF2563EB)
val GoogleBlueDark = Color(0xFF1D4ED8)
val GoogleBlueLight = Color(0xFFEFF6FF)
val GoogleBlueContainer = Color(0xFFDBEAFE)

val SoftTeal = Color(0xFF0D9488)
val SoftTealDark = Color(0xFF115E59)
val SoftTealLight = Color(0xFFF0FDFA)
val SoftTealBorder = Color(0xFF99F6E4)

val SoftLavender = Color(0xFF7C3AED)
val SoftLavenderDark = Color(0xFF5B21B6)
val SoftLavenderLight = Color(0xFFF5F3FF)
val SoftLavenderBorder = Color(0xFFDDD6FE)

val WarmCoral = Color(0xFFEA580C)
val WarmCoralDark = Color(0xFF9A3412)
val WarmCoralLight = Color(0xFFFFF7ED)
val WarmCoralBorder = Color(0xFFFED7AA)

val GoogleGreen = Color(0xFF16A34A)
val GoogleGreenDark = Color(0xFF15803D)
val GoogleGreenLight = Color(0xFFECFDF5)

val GoogleYellow = Color(0xFFD97706)
val GoogleYellowDark = Color(0xFF92400E)
val GoogleYellowLight = Color(0xFFFEF3C7)

val GoogleRed = Color(0xFFDC2626)
val GoogleRedDark = Color(0xFF991B1B)
val GoogleRedLight = Color(0xFFFEF2F2)

val GooglePurple = Color(0xFF7C3AED)
val GooglePurpleLight = Color(0xFFF5F3FF)

// Soft Glass Light Surfaces
val BackgroundLight = Color(0xFFF3F7FD)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceVelvetLight = Color(0xFFF8FAFC)
val SurfaceVariantLight = Color(0xFFEFF4FA)
val OutlineLight = Color(0xFFE2E8F0)
val TextPrimary = Color(0xFF0F172A)
val TextSecondary = Color(0xFF64748B)

// Soft-Tactile Category & Card Accents
val PastelYellowBackground = Color(0xFFFEFBF3)
val PastelYellowBorder = Color(0xFFF6E0B5)
val PastelYellowAccent = Color(0xFFD97706)
val PastelYellowDark = Color(0xFF78350F)

val PastelGreenBackground = Color(0xFFF0FDF4)
val PastelGreenBorder = Color(0xFFBBF7D0)
val PastelGreenAccent = Color(0xFF15803D)
val PastelGreenDark = Color(0xFF14532D)

val PastelPurpleBackground = Color(0xFFF5F3FF)
val PastelPurpleBorder = Color(0xFFDDD6FE)
val PastelPurpleAccent = Color(0xFF6D28D9)
val PastelPurpleDark = Color(0xFF4C1D95)

val PastelBlueBackground = Color(0xFFF0F7FF)
val PastelBlueBorder = Color(0xFFBFDBFE)
val PastelBlueAccent = Color(0xFF2563EB)
val PastelBlueDark = Color(0xFF1E3A8A)

val VibrantActionBlue = Color(0xFF2563EB)
val VibrantActionBlueHover = Color(0xFF1D4ED8)

// Soft Glass Dark Surfaces (#0B1120 / #0F172A background, #1E293B cards, #60A5FA accents)
val BackgroundDark = Color(0xFF0B1120)
val SurfaceDark = Color(0xFF1E293B)
val SurfaceVariantDark = Color(0xFF273549)
val OutlineDark = Color(0xFF334155)
val TextPrimaryDark = Color(0xFFF8FAFC)
val TextSecondaryDark = Color(0xFF94A3B8)

@Immutable
data class SoftGlassColors(
    val isDark: Boolean,
    val screenBackground: Color,
    val screenGradientTop: Color,
    val screenGradientBottom: Color,
    val cardBackground: Color,
    val cardElevatedBackground: Color,
    val cardTintedBlueBackground: Color,
    val cardBorder: Color,
    val cardSelectedBorder: Color,
    val primaryAccent: Color,
    val primaryAccentSoftBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val successText: Color,
    val successBg: Color,
    val successBorder: Color,
    val warningText: Color,
    val warningBg: Color,
    val dangerText: Color,
    val dangerBg: Color,
    val divider: Color
) {
    val screenBackgroundTop: Color get() = screenBackground
    val screenBackgroundMid: Color get() = screenGradientTop
    val screenBackgroundBottom: Color get() = screenGradientBottom
    val cardElevated: Color get() = if (isDark) cardBackground else cardElevatedBackground
    val cardBorderStrong: Color get() = cardSelectedBorder
    val primaryAccentSoft: Color get() = primaryAccentSoftBg
    val secondaryContainer: Color get() = if (isDark) Color(0xFF273549) else Color(0xFFF1F5F9)
    val successContainer: Color get() = successBg
    val warningContainer: Color get() = warningBg
    val dangerContainer: Color get() = dangerBg
}

val LightSoftGlassColors = SoftGlassColors(
    isDark = false,
    screenBackground = Color(0xFFF3F7FD),
    screenGradientTop = Color(0xFFEAF2FC),
    screenGradientBottom = Color(0xFFF8FAFC),
    cardBackground = Color(0xFFFFFFFF),
    cardElevatedBackground = Color(0xFFFBFDFF),
    cardTintedBlueBackground = Color(0xFFEFF6FF),
    cardBorder = Color(0xFFE2E8F0),
    cardSelectedBorder = Color(0xFF93C5FD),
    primaryAccent = Color(0xFF2563EB),
    primaryAccentSoftBg = Color(0xFFDBEAFE),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF64748B),
    successText = Color(0xFF15803D),
    successBg = Color(0xFFECFDF5),
    successBorder = Color(0xFFBBF7D0),
    warningText = Color(0xFFB45309),
    warningBg = Color(0xFFFEF3C7),
    dangerText = Color(0xFFDC2626),
    dangerBg = Color(0xFFFEF2F2),
    divider = Color(0xFFE2E8F0)
)

val DarkSoftGlassColors = SoftGlassColors(
    isDark = true,
    screenBackground = Color(0xFF0B1120),
    screenGradientTop = Color(0xFF0F172A),
    screenGradientBottom = Color(0xFF0B1120),
    cardBackground = Color(0xFF1E293B),
    cardElevatedBackground = Color(0xFF1E293B),
    cardTintedBlueBackground = Color(0xFF172554),
    cardBorder = Color(0xFF334155),
    cardSelectedBorder = Color(0xFF60A5FA),
    primaryAccent = Color(0xFF60A5FA),
    primaryAccentSoftBg = Color(0xFF1E3A8A),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFFCBD5E1),
    textMuted = Color(0xFF94A3B8),
    successText = Color(0xFF4ADE80),
    successBg = Color(0xFF064E3B),
    successBorder = Color(0xFF059669),
    warningText = Color(0xFFFBBF24),
    warningBg = Color(0xFF451A03),
    dangerText = Color(0xFFF87171),
    dangerBg = Color(0xFF450A0A),
    divider = Color(0xFF334155)
)

val LocalSoftGlassColors = staticCompositionLocalOf { LightSoftGlassColors }
