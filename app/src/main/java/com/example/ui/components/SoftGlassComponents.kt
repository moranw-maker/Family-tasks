package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.AccessAlarm
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppScreen
import com.example.model.FamilyMember
import com.example.ui.theme.LocalSoftGlassColors

/**
 * Modifier that draws a soft top inner specular highlight sheen and subtle bottom inner depth
 * to create a tactile Luxury Soft Neumorphism 3D surface effect.
 */
fun Modifier.velvetNeumorphicSheen(
    isDark: Boolean,
    cornerRadius: Dp = 24.dp,
    accentColor: Color? = null,
    isRecessed: Boolean = false
): Modifier = this.drawWithContent {
    drawContent()
    val strokePx = 1.35.dp.toPx()
    val radiusPx = cornerRadius.toPx()
    if (isRecessed) {
        // Recessed / inset depth: subtle top inner shadow + bottom inner highlight
        val insetTopShadow = if (isDark) {
            Color(0xFF020617).copy(alpha = 0.65f)
        } else {
            Color(0xFF94A3B8).copy(alpha = 0.28f)
        }
        val insetBottomHighlight = if (isDark) {
            Color(0xFF60A5FA).copy(alpha = 0.16f)
        } else {
            Color.White.copy(alpha = 0.90f)
        }
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    insetTopShadow,
                    Color.Transparent,
                    insetBottomHighlight
                )
            ),
            topLeft = Offset(strokePx / 2f, strokePx / 2f),
            size = Size(
                width = (size.width - strokePx).coerceAtLeast(0f),
                height = (size.height - strokePx).coerceAtLeast(0f)
            ),
            cornerRadius = CornerRadius(radiusPx, radiusPx),
            style = Stroke(width = strokePx)
        )
    } else {
        // Raised 3D Velvet Neumorphic surface: bright top specular highlight + soft bottom depth
        val topHighlightStart = when {
            isDark && accentColor != null -> accentColor.copy(alpha = 0.48f)
            isDark -> Color(0xFF93C5FD).copy(alpha = 0.34f)
            else -> Color.White.copy(alpha = 0.96f)
        }
        val topHighlightMid = when {
            isDark -> Color(0xFFC4B5FD).copy(alpha = 0.22f)
            else -> Color.White.copy(alpha = 0.55f)
        }
        val bottomRimShade = if (isDark) {
            Color(0xFF020617).copy(alpha = 0.50f)
        } else {
            Color(0xFF64748B).copy(alpha = 0.10f)
        }

        // Outer inner-rim highlight & depth contour
        drawRoundRect(
            brush = Brush.verticalGradient(
                0.0f to topHighlightStart,
                0.28f to topHighlightMid,
                0.70f to Color.Transparent,
                1.0f to bottomRimShade
            ),
            topLeft = Offset(strokePx / 2f, strokePx / 2f),
            size = Size(
                width = (size.width - strokePx).coerceAtLeast(0f),
                height = (size.height - strokePx).coerceAtLeast(0f)
            ),
            cornerRadius = CornerRadius(radiusPx, radiusPx),
            style = Stroke(width = strokePx)
        )

        // Subtle diffused top glow band (soft velvet lighting from above)
        val topGlowHeight = (size.height * 0.26f).coerceAtMost(28.dp.toPx())
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    if (isDark) {
                        (accentColor ?: Color(0xFF60A5FA)).copy(alpha = 0.08f)
                    } else {
                        Color.White.copy(alpha = 0.50f)
                    },
                    Color.Transparent
                ),
                startY = 0f,
                endY = topGlowHeight
            ),
            topLeft = Offset(0f, 0f),
            size = Size(size.width, topGlowHeight),
            cornerRadius = CornerRadius(radiusPx, radiusPx)
        )
    }
}

/**
 * Luxury Soft Neumorphism surface container with a 3-stop velvet gradient fill,
 * diffused colored ambient/spot shadow, subtle translucent border, and top inner specular sheen.
 */
@Composable
fun FamilyGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    isTintedBlue: Boolean = false,
    isSelected: Boolean = false,
    accentColor: Color? = null,
    elevation: Dp = 6.dp,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val shape = RoundedCornerShape(cornerRadius)
    val effectiveElevation = if (isSelected) (elevation + 3.dp) else elevation

    val ambientShadow = if (glass.isDark) {
        Color(0xFF000000).copy(alpha = 0.78f)
    } else {
        (accentColor ?: Color(0xFF1E3A8A)).copy(alpha = 0.14f)
    }
    val spotShadow = if (glass.isDark) {
        (accentColor ?: Color(0xFF020617)).copy(alpha = 0.88f)
    } else {
        (accentColor ?: Color(0xFF1E40AF)).copy(alpha = 0.22f)
    }

    val gradientBrush = when {
        accentColor != null -> Brush.linearGradient(
            colors = if (glass.isDark) {
                listOf(
                    Color(0xFF212D48),
                    accentColor.copy(alpha = 0.20f),
                    Color(0xFF131C2E)
                )
            } else {
                listOf(
                    Color(0xFFFFFFFF),
                    Color(0xFFF9FBFF),
                    accentColor.copy(alpha = 0.11f)
                )
            },
            start = Offset(0f, 0f),
            end = Offset(900f, 650f)
        )
        isTintedBlue -> Brush.linearGradient(
            colors = if (glass.isDark) {
                listOf(
                    Color(0xFF1E2C4C),
                    Color(0xFF17223B),
                    Color(0xFF1A1936)
                )
            } else {
                listOf(
                    Color(0xFFFFFFFF),
                    Color(0xFFF3F7FE),
                    Color(0xFFE7EFFD)
                )
            },
            start = Offset(0f, 0f),
            end = Offset(900f, 650f)
        )
        else -> Brush.verticalGradient(
            colors = if (glass.isDark) {
                listOf(
                    Color(0xFF1F2B44),
                    Color(0xFF172135),
                    Color(0xFF121A2B)
                )
            } else {
                listOf(
                    Color(0xFFFFFFFF),
                    Color(0xFFFAFBFD),
                    Color(0xFFF0F4FC)
                )
            }
        )
    }

    val borderBrush = when {
        isSelected -> Brush.linearGradient(
            colors = listOf(
                glass.primaryAccent.copy(alpha = 0.85f),
                glass.velvetVioletAccent.copy(alpha = 0.70f)
            )
        )
        accentColor != null -> Brush.linearGradient(
            colors = listOf(
                if (glass.isDark) accentColor.copy(alpha = 0.52f) else Color.White.copy(alpha = 0.95f),
                accentColor.copy(alpha = if (glass.isDark) 0.24f else 0.28f)
            )
        )
        isTintedBlue -> Brush.linearGradient(
            colors = if (glass.isDark) {
                listOf(
                    glass.primaryAccent.copy(alpha = 0.42f),
                    glass.velvetVioletAccent.copy(alpha = 0.26f)
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.95f),
                    Color(0xFFBFD3F8).copy(alpha = 0.65f)
                )
            }
        )
        else -> Brush.linearGradient(
            colors = if (glass.isDark) {
                listOf(
                    Color(0xFF475B7F).copy(alpha = 0.55f),
                    Color(0xFF25324B).copy(alpha = 0.45f)
                )
            } else {
                listOf(
                    Color.White.copy(alpha = 0.98f),
                    Color(0xFFD5E0F2).copy(alpha = 0.75f)
                )
            }
        )
    }

    Surface(
        shape = shape,
        color = Color.Transparent,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        border = BorderStroke(if (isSelected) 1.4.dp else 0.85.dp, borderBrush),
        modifier = modifier
            .shadow(
                elevation = effectiveElevation,
                shape = shape,
                ambientColor = ambientShadow,
                spotColor = spotShadow
            )
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
                .velvetNeumorphicSheen(
                    isDark = glass.isDark,
                    cornerRadius = cornerRadius,
                    accentColor = accentColor
                )
                .padding(contentPadding),
            content = content
        )
    }
}

/**
 * Recessed / Carved Velvet Neumorphic Search Container.
 * Gives search bars a genuine soft-inset 3D tactile look in both Velvet Light and Velvet Dark.
 */
@Composable
fun VelvetSearchContainer(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val shape = RoundedCornerShape(cornerRadius)
    val recessedBrush = Brush.verticalGradient(
        colors = if (glass.isDark) {
            listOf(
                Color(0xFF0B1220),
                Color(0xFF111A2E),
                Color(0xFF162138)
            )
        } else {
            listOf(
                Color(0xFFE9EFFA),
                Color(0xFFF2F6FD),
                Color(0xFFFFFFFF)
            )
        }
    )
    val borderBrush = Brush.verticalGradient(
        colors = if (glass.isDark) {
            listOf(
                Color(0xFF060A14).copy(alpha = 0.8f),
                glass.primaryAccent.copy(alpha = 0.28f)
            )
        } else {
            listOf(
                Color(0xFFCBD6EA).copy(alpha = 0.85f),
                Color.White.copy(alpha = 0.95f)
            )
        }
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(recessedBrush)
            .border(0.9.dp, borderBrush, shape)
            .velvetNeumorphicSheen(
                isDark = glass.isDark,
                cornerRadius = cornerRadius,
                isRecessed = true
            ),
        content = content
    )
}

/**
 * Raised 3D Luxury Soft Neumorphic Primary Button with multi-stop sapphire/velvet gradient,
 * diffused glow shadow, and top specular highlight edge.
 */
@Composable
fun FamilyPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    leadingEmoji: String? = null,
    containerColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
) {
    val glass = LocalSoftGlassColors.current
    val buttonShape = RoundedCornerShape(16.dp)
    val baseColor = containerColor ?: if (glass.isDark) Color(0xFF3B82F6) else Color(0xFF2563EB)

    val fgColor = if (!enabled) {
        glass.textMuted
    } else if (containerColor != null && containerColor.luminance() > 0.65f) {
        Color(0xFF0F172A)
    } else {
        Color.White
    }

    val buttonBrush = if (!enabled) {
        Brush.verticalGradient(
            colors = listOf(
                glass.cardBorder.copy(alpha = 0.6f),
                glass.cardBorder.copy(alpha = 0.4f)
            )
        )
    } else if (containerColor != null) {
        Brush.verticalGradient(
            colors = listOf(
                Color(
                    red = (containerColor.red + 0.10f).coerceAtMost(1f),
                    green = (containerColor.green + 0.10f).coerceAtMost(1f),
                    blue = (containerColor.blue + 0.10f).coerceAtMost(1f),
                    alpha = containerColor.alpha
                ),
                containerColor,
                Color(
                    red = (containerColor.red * 0.82f).coerceIn(0f, 1f),
                    green = (containerColor.green * 0.82f).coerceIn(0f, 1f),
                    blue = (containerColor.blue * 0.86f).coerceIn(0f, 1f),
                    alpha = containerColor.alpha
                )
            )
        )
    } else if (glass.isDark) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF60A5FA),
                Color(0xFF3B82F6),
                Color(0xFF4F46E5)
            ),
            start = Offset(0f, 0f),
            end = Offset(320f, 140f)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF3B82F6),
                Color(0xFF2563EB),
                Color(0xFF1D4ED8)
            ),
            start = Offset(0f, 0f),
            end = Offset(320f, 140f)
        )
    }

    val topRimBrush = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = if (glass.isDark) 0.42f else 0.55f),
            baseColor.copy(alpha = 0.25f)
        )
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = buttonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = fgColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = glass.textMuted
        ),
        contentPadding = contentPadding,
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            disabledElevation = 0.dp
        ),
        modifier = modifier
            .heightIn(min = 42.dp)
            .shadow(
                elevation = if (enabled) 7.dp else 0.dp,
                shape = buttonShape,
                ambientColor = baseColor.copy(alpha = if (glass.isDark) 0.55f else 0.30f),
                spotColor = baseColor.copy(alpha = if (glass.isDark) 0.70f else 0.42f)
            )
            .clip(buttonShape)
            .background(buttonBrush, buttonShape)
            .border(0.85.dp, topRimBrush, buttonShape)
            .drawWithContent {
                drawContent()
                if (enabled) {
                    val topBand = (size.height * 0.38f).coerceAtMost(16.dp.toPx())
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.26f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = topBand
                        ),
                        size = Size(size.width, topBand),
                        cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())
                    )
                }
            }
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        } else if (!leadingEmoji.isNullOrBlank()) {
            Text(text = leadingEmoji, fontSize = 15.sp)
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
        )
    }
}

/**
 * Raised Pearl / Graphite Soft Neumorphic Secondary Button with subtle 3D elevation and sheen.
 */
@Composable
fun FamilySecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    contentColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
) {
    val glass = LocalSoftGlassColors.current
    val fg = contentColor ?: glass.textPrimary
    val buttonShape = RoundedCornerShape(16.dp)
    val surfaceGradient = Brush.verticalGradient(
        colors = if (glass.isDark) {
            listOf(
                Color(0xFF24324D),
                Color(0xFF19243A)
            )
        } else {
            listOf(
                Color(0xFFFFFFFF),
                Color(0xFFF1F5FD)
            )
        }
    )
    val borderBrush = Brush.verticalGradient(
        colors = if (glass.isDark) {
            listOf(
                glass.primaryAccent.copy(alpha = 0.45f),
                glass.cardBorder
            )
        } else {
            listOf(
                Color.White,
                glass.primaryAccent.copy(alpha = 0.30f)
            )
        }
    )

    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = buttonShape,
        border = BorderStroke(0.95.dp, borderBrush),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = fg
        ),
        contentPadding = contentPadding,
        modifier = modifier
            .heightIn(min = 40.dp)
            .shadow(
                elevation = if (enabled) 4.dp else 0.dp,
                shape = buttonShape,
                ambientColor = if (glass.isDark) Color.Black.copy(alpha = 0.65f) else Color(0xFF1E3A8A).copy(alpha = 0.12f),
                spotColor = if (glass.isDark) Color.Black.copy(alpha = 0.75f) else Color(0xFF1E3A8A).copy(alpha = 0.18f)
            )
            .clip(buttonShape)
            .background(surfaceGradient, buttonShape)
            .velvetNeumorphicSheen(isDark = glass.isDark, cornerRadius = 16.dp)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = contentColor ?: glass.primaryAccent,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
        )
    }
}

/**
 * Unified Soft Glass Section Header with vertical accent pill indicator and optional action chip.
 */
@Composable
fun FamilySectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    val glass = LocalSoftGlassColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .width(4.5.dp)
                    .height(if (subtitle != null) 28.dp else 20.dp)
                    .shadow(
                        elevation = 3.dp,
                        shape = RoundedCornerShape(50),
                        spotColor = glass.primaryAccent
                    )
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                glass.primaryAccent,
                                glass.velvetVioletAccent.copy(alpha = 0.75f)
                            )
                        )
                    )
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = glass.textPrimary
                    )
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = glass.textSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }
        if (!actionLabel.isNullOrBlank() && onActionClick != null) {
            val chipShape = RoundedCornerShape(14.dp)
            Surface(
                shape = chipShape,
                color = Color.Transparent,
                border = BorderStroke(0.9.dp, glass.primaryAccent.copy(alpha = 0.32f)),
                modifier = Modifier
                    .shadow(
                        elevation = 3.dp,
                        shape = chipShape,
                        spotColor = glass.primaryAccent.copy(alpha = 0.22f)
                    )
                    .clip(chipShape)
                    .background(
                        Brush.verticalGradient(
                            colors = if (glass.isDark) {
                                listOf(Color(0xFF233863), glass.primaryAccentSoftBg)
                            } else {
                                listOf(Color.White, glass.primaryAccentSoftBg)
                            }
                        )
                    )
                    .clickable(onClick = onActionClick)
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = glass.primaryAccent
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val glass = LocalSoftGlassColors.current
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = glass.textPrimary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = glass.textSecondary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            if (onNavigateBack != null) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = glass.textPrimary
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = glass.cardBackground
        ),
        modifier = modifier
    )
}

@Composable
fun FamilyEmptyState(
    emoji: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    val glass = LocalSoftGlassColors.current
    FamilyGlassCard(
        modifier = modifier.fillMaxWidth(),
        isTintedBlue = true,
        cornerRadius = 24.dp,
        elevation = 7.dp,
        contentPadding = PaddingValues(vertical = 28.dp, horizontal = 20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val orbShape = RoundedCornerShape(20.dp)
            Surface(
                shape = orbShape,
                color = Color.Transparent,
                border = BorderStroke(1.dp, glass.primaryAccent.copy(alpha = 0.28f)),
                modifier = Modifier
                    .size(66.dp)
                    .shadow(
                        elevation = 6.dp,
                        shape = orbShape,
                        spotColor = glass.primaryAccent.copy(alpha = 0.25f)
                    )
                    .background(
                        Brush.verticalGradient(
                            colors = if (glass.isDark) {
                                listOf(Color(0xFF253554), Color(0xFF162035))
                            } else {
                                listOf(Color.White, Color(0xFFEEF4FE))
                            }
                        ),
                        orbShape
                    )
                    .velvetNeumorphicSheen(isDark = glass.isDark, cornerRadius = 20.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = emoji, fontSize = 30.sp)
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = glass.textPrimary
                ),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = glass.textSecondary,
                    fontWeight = FontWeight.Medium
                ),
                textAlign = TextAlign.Center
            )
            if (!actionLabel.isNullOrBlank() && onActionClick != null) {
                Spacer(modifier = Modifier.height(16.dp))
                FamilyPrimaryButton(
                    text = actionLabel,
                    onClick = onActionClick
                )
            }
        }
    }
}

@Composable
fun FamilyStatusChip(
    label: String,
    modifier: Modifier = Modifier,
    emoji: String? = null,
    containerColor: Color = LocalSoftGlassColors.current.primaryAccentSoftBg,
    contentColor: Color = LocalSoftGlassColors.current.primaryAccent,
    borderColor: Color = contentColor.copy(alpha = 0.25f)
) {
    val glass = LocalSoftGlassColors.current
    val chipShape = RoundedCornerShape(10.dp)
    Surface(
        shape = chipShape,
        color = Color.Transparent,
        border = BorderStroke(0.8.dp, borderColor),
        modifier = modifier
            .clip(chipShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        if (glass.isDark) containerColor.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.65f),
                        containerColor
                    )
                ),
                chipShape
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!emoji.isNullOrBlank()) {
                Text(text = emoji, fontSize = 11.sp)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun FamilyMemberAvatar(
    member: FamilyMember,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    isHighlighted: Boolean = false
) {
    val glass = LocalSoftGlassColors.current
    val memberColor = Color(member.colorHex)
    Surface(
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(
            width = if (isHighlighted) 1.8.dp else 1.1.dp,
            brush = Brush.verticalGradient(
                colors = if (isHighlighted) {
                    listOf(
                        if (glass.isDark) Color.White.copy(alpha = 0.85f) else glass.primaryAccent,
                        glass.primaryAccent
                    )
                } else {
                    listOf(
                        Color.White.copy(alpha = if (glass.isDark) 0.45f else 0.90f),
                        memberColor.copy(alpha = 0.65f)
                    )
                }
            )
        ),
        modifier = modifier
            .size(size)
            .shadow(
                elevation = if (isHighlighted) 5.dp else 2.dp,
                shape = CircleShape,
                ambientColor = memberColor.copy(alpha = 0.35f),
                spotColor = memberColor.copy(alpha = 0.45f)
            )
            .background(
                Brush.radialGradient(
                    colors = if (glass.isDark) {
                        listOf(
                            memberColor.copy(alpha = 0.42f),
                            memberColor.copy(alpha = 0.22f)
                        )
                    } else {
                        listOf(
                            Color.White,
                            memberColor.copy(alpha = 0.24f)
                        )
                    }
                ),
                CircleShape
            )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = member.avatarEmoji,
                fontSize = (size.value * 0.48f).sp
            )
        }
    }
}

@Composable
fun FamilySettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingEmoji: String? = null,
    leadingIcon: ImageVector? = null,
    iconTint: Color = LocalSoftGlassColors.current.primaryAccent,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val glass = LocalSoftGlassColors.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (leadingIcon != null || !leadingEmoji.isNullOrBlank()) {
                val badgeShape = RoundedCornerShape(13.dp)
                Surface(
                    shape = badgeShape,
                    color = Color.Transparent,
                    border = BorderStroke(0.9.dp, iconTint.copy(alpha = 0.30f)),
                    modifier = Modifier
                        .size(40.dp)
                        .shadow(
                            elevation = 3.dp,
                            shape = badgeShape,
                            spotColor = iconTint.copy(alpha = 0.25f)
                        )
                        .background(
                            Brush.verticalGradient(
                                colors = if (glass.isDark) {
                                    listOf(
                                        iconTint.copy(alpha = 0.30f),
                                        iconTint.copy(alpha = 0.16f)
                                    )
                                } else {
                                    listOf(
                                        Color.White,
                                        iconTint.copy(alpha = 0.16f)
                                    )
                                }
                            ),
                            badgeShape
                        )
                        .velvetNeumorphicSheen(isDark = glass.isDark, cornerRadius = 13.dp, accentColor = iconTint)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (leadingIcon != null) {
                            Icon(
                                imageVector = leadingIcon,
                                contentDescription = null,
                                tint = iconTint,
                                modifier = Modifier.size(20.dp)
                            )
                        } else if (!leadingEmoji.isNullOrBlank()) {
                            Text(text = leadingEmoji, fontSize = 18.sp)
                        }
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = glass.textPrimary
                    )
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = glass.textSecondary,
                            fontSize = 11.5.sp
                        )
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (trailingContent != null) {
            trailingContent()
        } else if (onClick != null) {
            Icon(
                imageVector = if (isRtl) {
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = null,
                tint = glass.textMuted
            )
        }
    }
}

@Composable
fun FamilyConfirmationDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = false
) {
    val glass = LocalSoftGlassColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        containerColor = glass.cardBackground,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = glass.textPrimary
                )
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = glass.textSecondary
                )
            )
        },
        confirmButton = {
            FamilyPrimaryButton(
                text = confirmText,
                onClick = onConfirm,
                containerColor = if (isDestructive) glass.dangerText else glass.primaryAccent
            )
        },
        dismissButton = {
            FamilySecondaryButton(
                text = dismissText,
                onClick = onDismiss
            )
        }
    )
}

private data class SoftGlassNavItem(
    val screen: AppScreen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

/**
 * Luxury Soft Neumorphism Docked Capsule Bottom Navigation Bar.
 * Renders 3D raised active pill indicators, velvet gradient dock, and luminous top specular edge.
 */
@Composable
fun FamilyBottomNavigation(
    currentScreen: AppScreen,
    isParent: Boolean,
    isHebrew: Boolean,
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalSoftGlassColors.current
    val items = buildList {
        add(
            SoftGlassNavItem(
                screen = AppScreen.HOME,
                label = if (isHebrew) "משימות" else "Tasks",
                selectedIcon = Icons.Filled.CheckCircle,
                unselectedIcon = Icons.Outlined.CheckCircle,
                testTag = "nav_home"
            )
        )
        add(
            SoftGlassNavItem(
                screen = AppScreen.CALENDAR,
                label = if (isHebrew) "לוח שנה" else "Calendar",
                selectedIcon = Icons.Filled.CalendarMonth,
                unselectedIcon = Icons.Outlined.CalendarMonth,
                testTag = "nav_calendar"
            )
        )
        add(
            SoftGlassNavItem(
                screen = AppScreen.TIMETABLE,
                label = if (isHebrew) "מערכת" else "Schedule",
                selectedIcon = Icons.Filled.Schedule,
                unselectedIcon = Icons.Outlined.Schedule,
                testTag = "nav_timetable"
            )
        )
        add(
            SoftGlassNavItem(
                screen = AppScreen.ALARMS,
                label = if (isHebrew) "שעונים" else "Alarms",
                selectedIcon = Icons.Filled.AccessAlarm,
                unselectedIcon = Icons.Outlined.AccessAlarm,
                testTag = "nav_alarms"
            )
        )
        add(
            SoftGlassNavItem(
                screen = AppScreen.FILES,
                label = if (isHebrew) "מסמכים" else "Files",
                selectedIcon = Icons.Filled.Folder,
                unselectedIcon = Icons.Outlined.Folder,
                testTag = "nav_files"
            )
        )
        if (isParent) {
            add(
                SoftGlassNavItem(
                    screen = AppScreen.ADMIN_DASHBOARD,
                    label = if (isHebrew) "משפחה" else "Family",
                    selectedIcon = Icons.Filled.AdminPanelSettings,
                    unselectedIcon = Icons.Outlined.AdminPanelSettings,
                    testTag = "nav_admin"
                )
            )
        }
    }

    val navShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val navBrush = Brush.verticalGradient(
        colors = if (glass.isDark) {
            listOf(
                Color(0xFF1D2942),
                Color(0xFF121B2E),
                Color(0xFF0A101D)
            )
        } else {
            listOf(
                Color(0xFFFFFFFF),
                Color(0xFFF6F9FE),
                Color(0xFFECF2FC)
            )
        }
    )

    Surface(
        shape = navShape,
        color = Color.Transparent,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        border = BorderStroke(
            0.95.dp,
            Brush.horizontalGradient(
                colors = if (glass.isDark) {
                    listOf(
                        glass.primaryAccent.copy(alpha = 0.48f),
                        glass.velvetVioletAccent.copy(alpha = 0.40f),
                        glass.cardBorder
                    )
                } else {
                    listOf(
                        Color.White,
                        glass.primaryAccent.copy(alpha = 0.30f),
                        Color.White
                    )
                }
            )
        ),
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = navShape,
                ambientColor = if (glass.isDark) Color.Black else Color(0xFF1E3A8A).copy(alpha = 0.18f),
                spotColor = if (glass.isDark) Color.Black else Color(0xFF1E3A8A).copy(alpha = 0.26f)
            )
            .testTag("bottom_navigation_bar")
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .background(navBrush)
                .velvetNeumorphicSheen(isDark = glass.isDark, cornerRadius = 28.dp)
                .navigationBarsPadding()
        ) {
            val isCompactWidth = maxWidth < 380.dp || items.size >= 6
            val iconSize = if (isCompactWidth) 20.dp else 22.dp
            val labelFontSize = if (isCompactWidth) 10.sp else 11.sp

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isCompactWidth) 6.dp else 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    val isSelected = currentScreen == item.screen
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) glass.primaryAccent else glass.textSecondary,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "navContentColor"
                    )
                    val pillShape = RoundedCornerShape(18.dp)

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp)
                            .then(
                                if (isSelected) {
                                    Modifier
                                        .shadow(
                                            elevation = 5.dp,
                                            shape = pillShape,
                                            ambientColor = glass.primaryAccent.copy(alpha = 0.25f),
                                            spotColor = glass.primaryAccent.copy(alpha = 0.35f)
                                        )
                                        .clip(pillShape)
                                        .background(
                                            Brush.verticalGradient(
                                                colors = if (glass.isDark) {
                                                    listOf(
                                                        Color(0xFF263B6A),
                                                        Color(0xFF1B2B4E)
                                                    )
                                                } else {
                                                    listOf(
                                                        Color(0xFFFFFFFF),
                                                        Color(0xFFDFEBFE)
                                                    )
                                                }
                                            ),
                                            pillShape
                                        )
                                        .border(
                                            0.9.dp,
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    if (glass.isDark) glass.primaryAccent.copy(alpha = 0.55f) else Color.White,
                                                    glass.primaryAccent.copy(alpha = 0.32f)
                                                )
                                            ),
                                            pillShape
                                        )
                                        .velvetNeumorphicSheen(
                                            isDark = glass.isDark,
                                            cornerRadius = 18.dp,
                                            accentColor = glass.primaryAccent
                                        )
                                } else {
                                    Modifier.clip(pillShape)
                                }
                            )
                            .clickable { onNavigate(item.screen) }
                            .padding(horizontal = 2.dp, vertical = 6.dp)
                            .defaultMinSize(minWidth = 44.dp, minHeight = 48.dp)
                            .testTag(item.testTag)
                    ) {
                        Icon(
                            imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.label,
                            tint = contentColor,
                            modifier = Modifier.size(iconSize)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.label,
                            fontSize = labelFontSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = contentColor,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * Velvet Edition Screen Background with soft pearl/silvery-white diffused lighting in Velvet Light
 * and deep blue-black/graphite with subtle sapphire & violet ambient glow in Velvet Dark.
 */
@Composable
fun SoftGlassScreenBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val baseGradient = Brush.verticalGradient(
        colors = if (glass.isDark) {
            listOf(
                Color(0xFF10182D),
                Color(0xFF0B1120),
                Color(0xFF060A14)
            )
        } else {
            listOf(
                Color(0xFFE9EFFA),
                Color(0xFFF3F6FC),
                Color(0xFFF8FAFE)
            )
        }
    )
    val topVelvetSheen = Brush.radialGradient(
        colors = if (glass.isDark) {
            listOf(
                glass.velvetVioletAccent.copy(alpha = 0.14f),
                glass.primaryAccent.copy(alpha = 0.10f),
                Color.Transparent
            )
        } else {
            listOf(
                Color.White.copy(alpha = 0.85f),
                Color(0xFFDBEAFE).copy(alpha = 0.35f),
                Color.Transparent
            )
        },
        center = Offset(240f, 80f),
        radius = 680f
    )
    val secondaryAmbientGlow = Brush.radialGradient(
        colors = if (glass.isDark) {
            listOf(
                Color(0xFF3B82F6).copy(alpha = 0.08f),
                Color.Transparent
            )
        } else {
            listOf(
                Color(0xFFE0E7FF).copy(alpha = 0.40f),
                Color.Transparent
            )
        },
        center = Offset(820f, 920f),
        radius = 640f
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseGradient)
            .background(topVelvetSheen)
            .background(secondaryAmbientGlow),
        content = content
    )
}

