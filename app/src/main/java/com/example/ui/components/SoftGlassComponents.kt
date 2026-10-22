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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 * Primary Soft Glass surface container with a subtle frost gradient fill,
 * dual-stop luminous border, and soft elevation shadow.
 */
@Composable
fun FamilyGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    isTintedBlue: Boolean = false,
    isSelected: Boolean = false,
    accentColor: Color? = null,
    elevation: Dp = 4.dp,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val shape = RoundedCornerShape(cornerRadius)

    val gradientBrush = when {
        accentColor != null -> Brush.linearGradient(
            colors = if (glass.isDark) {
                listOf(
                    accentColor.copy(alpha = 0.24f),
                    glass.cardBackground.copy(alpha = 0.96f),
                    accentColor.copy(alpha = 0.12f)
                )
            } else {
                listOf(
                    Color.White,
                    accentColor.copy(alpha = 0.10f),
                    accentColor.copy(alpha = 0.18f)
                )
            },
            start = Offset(0f, 0f),
            end = Offset(900f, 600f)
        )
        isTintedBlue -> Brush.linearGradient(
            colors = if (glass.isDark) {
                listOf(
                    Color(0xFF1E3A8A).copy(alpha = 0.55f),
                    glass.cardTintedBlueBackground,
                    Color(0xFF1E293B)
                )
            } else {
                listOf(
                    Color(0xFFF8FBFF),
                    Color(0xFFEAF3FF),
                    Color(0xFFDBEAFE).copy(alpha = 0.65f)
                )
            },
            start = Offset(0f, 0f),
            end = Offset(900f, 600f)
        )
        else -> Brush.verticalGradient(
            colors = if (glass.isDark) {
                listOf(
                    Color(0xFF243247),
                    glass.cardBackground
                )
            } else {
                listOf(
                    Color(0xFFFFFFFF),
                    Color(0xFFF6FAFF)
                )
            }
        )
    }

    val borderBrush = when {
        isSelected -> Brush.linearGradient(
            colors = listOf(
                glass.primaryAccent,
                glass.cardSelectedBorder
            )
        )
        accentColor != null -> Brush.linearGradient(
            colors = listOf(
                accentColor.copy(alpha = if (glass.isDark) 0.65f else 0.50f),
                accentColor.copy(alpha = if (glass.isDark) 0.30f else 0.22f)
            )
        )
        isTintedBlue -> Brush.linearGradient(
            colors = if (glass.isDark) {
                listOf(
                    glass.primaryAccent.copy(alpha = 0.55f),
                    glass.cardBorder
                )
            } else {
                listOf(
                    Color(0xFF93C5FD),
                    Color(0xFFBFDBFE)
                )
            }
        )
        else -> Brush.linearGradient(
            colors = if (glass.isDark) {
                listOf(
                    Color(0xFF475569).copy(alpha = 0.75f),
                    glass.cardBorder
                )
            } else {
                listOf(
                    Color(0xFFCBD5E1).copy(alpha = 0.85f),
                    Color(0xFFE2E8F0)
                )
            }
        )
    }

    Surface(
        shape = shape,
        color = Color.Transparent,
        shadowElevation = elevation,
        tonalElevation = elevation,
        border = BorderStroke(if (isSelected) 1.8.dp else 1.1.dp, borderBrush),
        modifier = if (onClick != null) {
            modifier
                .clip(shape)
                .clickable(onClick = onClick)
        } else {
            modifier.clip(shape)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
                .padding(contentPadding),
            content = content
        )
    }
}

/**
 * Tactile Soft Glass Primary Button with luminous blue gradient and squircle corners.
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
    val activeBg = containerColor ?: glass.primaryAccent
    val fgColor = if (containerColor != null) {
        Color.White
    } else {
        if (glass.isDark) Color(0xFF0B1120) else Color.White
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = activeBg,
            contentColor = fgColor,
            disabledContainerColor = glass.cardBorder,
            disabledContentColor = glass.textMuted
        ),
        contentPadding = contentPadding,
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 3.dp,
            pressedElevation = 6.dp
        ),
        modifier = modifier.heightIn(min = 42.dp)
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
 * Translucent Soft Glass Secondary Button with subtle border and frosted surface.
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
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.2.dp, glass.primaryAccent.copy(alpha = 0.35f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = glass.cardBackground.copy(alpha = 0.9f),
            contentColor = fg
        ),
        contentPadding = contentPadding,
        modifier = modifier.heightIn(min = 40.dp)
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
                    .width(4.dp)
                    .height(if (subtitle != null) 28.dp else 20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                glass.primaryAccent,
                                glass.primaryAccent.copy(alpha = 0.45f)
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
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = glass.primaryAccentSoftBg,
                border = BorderStroke(1.dp, glass.primaryAccent.copy(alpha = 0.3f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
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
        contentPadding = PaddingValues(vertical = 28.dp, horizontal = 20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = glass.cardBackground,
                border = BorderStroke(1.dp, glass.primaryAccent.copy(alpha = 0.28f)),
                shadowElevation = 2.dp,
                modifier = Modifier.size(64.dp)
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
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = BorderStroke(0.8.dp, borderColor),
        modifier = modifier
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
        color = memberColor.copy(alpha = if (glass.isDark) 0.30f else 0.18f),
        border = BorderStroke(
            width = if (isHighlighted) 2.dp else 1.2.dp,
            color = if (isHighlighted) glass.primaryAccent else memberColor.copy(alpha = 0.65f)
        ),
        shadowElevation = if (isHighlighted) 2.dp else 0.dp,
        modifier = modifier.size(size)
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
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = iconTint.copy(alpha = if (glass.isDark) 0.24f else 0.14f),
                    border = BorderStroke(1.dp, iconTint.copy(alpha = 0.28f)),
                    modifier = Modifier.size(40.dp)
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
 * Elevated Soft Glass Docked Capsule Bottom Navigation Bar.
 * Renders distinct pill-shaped active indicators, frosted glass surface, and luminous top border.
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

    val navShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    val navBrush = Brush.verticalGradient(
        colors = if (glass.isDark) {
            listOf(
                Color(0xFF1E293B).copy(alpha = 0.98f),
                Color(0xFF0F172A)
            )
        } else {
            listOf(
                Color(0xFFFFFFFF),
                Color(0xFFF1F7FF)
            )
        }
    )

    Surface(
        shape = navShape,
        color = Color.Transparent,
        shadowElevation = 14.dp,
        tonalElevation = 6.dp,
        border = BorderStroke(
            1.2.dp,
            Brush.verticalGradient(
                colors = listOf(
                    glass.primaryAccent.copy(alpha = if (glass.isDark) 0.45f else 0.30f),
                    glass.cardBorder
                )
            )
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("bottom_navigation_bar")
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .background(navBrush)
                .navigationBarsPadding()
        ) {
            val isCompactWidth = maxWidth < 380.dp || items.size >= 6
            val iconSize = if (isCompactWidth) 20.dp else 22.dp
            val labelFontSize = if (isCompactWidth) 10.sp else 11.sp

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isCompactWidth) 6.dp else 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    val isSelected = currentScreen == item.screen
                    val pillColor by animateColorAsState(
                        targetValue = if (isSelected) glass.primaryAccentSoftBg else Color.Transparent,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "navPillColor"
                    )
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) glass.primaryAccent else glass.textSecondary,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "navContentColor"
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { onNavigate(item.screen) }
                            .background(pillColor)
                            .then(
                                if (isSelected) {
                                    Modifier.border(
                                        1.dp,
                                        glass.primaryAccent.copy(alpha = 0.35f),
                                        RoundedCornerShape(18.dp)
                                    )
                                } else {
                                    Modifier
                                }
                            )
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
 * Signature Soft Glass Screen Background with multi-stop vertical gradient and subtle
 * ambient radial light orbs in the top corners so frosted cards stand out clearly.
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
                Color(0xFF0F172A),
                Color(0xFF0B1120),
                Color(0xFF111827)
            )
        } else {
            listOf(
                Color(0xFFE6F0FD),
                Color(0xFFF2F7FE),
                Color(0xFFF8FAFC)
            )
        }
    )
    val topOrbGradient = Brush.radialGradient(
        colors = if (glass.isDark) {
            listOf(
                glass.primaryAccent.copy(alpha = 0.16f),
                Color.Transparent
            )
        } else {
            listOf(
                Color(0xFF93C5FD).copy(alpha = 0.32f),
                Color.Transparent
            )
        },
        center = Offset(180f, 120f),
        radius = 580f
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseGradient)
            .background(topOrbGradient),
        content = content
    )
}
