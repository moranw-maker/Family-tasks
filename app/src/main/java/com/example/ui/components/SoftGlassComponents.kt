package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppScreen
import com.example.model.FamilyMember
import com.example.ui.theme.LocalSoftGlassColors

@Composable
fun FamilyGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    isTintedBlue: Boolean = false,
    isSelected: Boolean = false,
    elevation: Dp = 2.dp,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val bgColor = when {
        isTintedBlue -> glass.cardTintedBlueBackground
        else -> glass.cardBackground
    }
    val borderColor = when {
        isSelected -> glass.cardSelectedBorder
        else -> glass.cardBorder
    }

    Surface(
        shape = RoundedCornerShape(cornerRadius),
        color = bgColor,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
        shadowElevation = elevation,
        tonalElevation = elevation,
        modifier = if (onClick != null) {
            modifier
                .clip(RoundedCornerShape(cornerRadius))
                .clickable(onClick = onClick)
        } else {
            modifier
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun FamilyPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    leadingEmoji: String? = null
) {
    val glass = LocalSoftGlassColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = glass.primaryAccent,
            contentColor = if (glass.isDark) Color(0xFF0B1120) else Color.White,
            disabledContainerColor = glass.cardBorder,
            disabledContentColor = glass.textMuted
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else if (!leadingEmoji.isNullOrBlank()) {
            Text(text = leadingEmoji, fontSize = 16.sp)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
        )
    }
}

@Composable
fun FamilySecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) {
    val glass = LocalSoftGlassColors.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, glass.cardBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = glass.cardBackground,
            contentColor = glass.textPrimary
        ),
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = glass.primaryAccent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary
                )
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = glass.textSecondary
                    )
                )
            }
        }
        if (!actionLabel.isNullOrBlank() && onActionClick != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = glass.primaryAccentSoftBg,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onActionClick)
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = glass.primaryAccent
                    ),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
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
                        fontWeight = FontWeight.Bold,
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
        contentPadding = PaddingValues(vertical = 28.dp, horizontal = 20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = glass.primaryAccentSoftBg.copy(alpha = 0.65f),
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = emoji, fontSize = 30.sp)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = glass.textPrimary
                ),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = glass.textSecondary
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
    contentColor: Color = LocalSoftGlassColors.current.primaryAccent
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
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
                )
            )
        }
    }
}

@Composable
fun FamilyMemberAvatar(
    member: FamilyMember,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val glass = LocalSoftGlassColors.current
    Surface(
        shape = CircleShape,
        color = Color(member.colorHex).copy(alpha = if (glass.isDark) 0.28f else 0.16f),
        border = BorderStroke(1.dp, Color(member.colorHex).copy(alpha = 0.55f)),
        modifier = modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = member.avatarEmoji,
                fontSize = (size.value * 0.5f).sp
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
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val glass = LocalSoftGlassColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
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
                    shape = RoundedCornerShape(12.dp),
                    color = glass.primaryAccentSoftBg.copy(alpha = if (glass.isDark) 0.5f else 0.7f),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (leadingIcon != null) {
                            Icon(
                                imageVector = leadingIcon,
                                contentDescription = null,
                                tint = glass.primaryAccent,
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
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = glass.textPrimary
                    )
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = glass.textSecondary
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
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
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
        shape = RoundedCornerShape(24.dp),
        containerColor = glass.cardBackground,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
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
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDestructive) glass.dangerText else glass.primaryAccent,
                    contentColor = Color.White
                )
            ) {
                Text(text = confirmText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = dismissText, color = glass.textSecondary, fontWeight = FontWeight.SemiBold)
            }
        }
    )
}

@Composable
fun FamilyBottomNavigation(
    currentScreen: AppScreen,
    isParent: Boolean,
    isHebrew: Boolean,
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalSoftGlassColors.current
    Surface(
        color = glass.cardBackground,
        shadowElevation = 10.dp,
        tonalElevation = 4.dp,
        border = BorderStroke(0.5.dp, glass.cardBorder)
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            modifier = modifier.testTag("bottom_navigation_bar")
        ) {
            NavigationBarItem(
                selected = currentScreen == AppScreen.HOME,
                onClick = { onNavigate(AppScreen.HOME) },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = if (isHebrew) "ראשי" else "Home"
                    )
                },
                label = {
                    Text(
                        text = if (isHebrew) "ראשי" else "Home",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (currentScreen == AppScreen.HOME) FontWeight.Bold else FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = glass.primaryAccent,
                    selectedTextColor = glass.primaryAccent,
                    indicatorColor = glass.primaryAccentSoftBg,
                    unselectedIconColor = glass.textMuted,
                    unselectedTextColor = glass.textMuted
                ),
                modifier = Modifier.testTag("nav_home")
            )

            NavigationBarItem(
                selected = currentScreen == AppScreen.CALENDAR,
                onClick = { onNavigate(AppScreen.CALENDAR) },
                icon = {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = if (isHebrew) "יומן" else "Calendar"
                    )
                },
                label = {
                    Text(
                        text = if (isHebrew) "יומן" else "Calendar",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (currentScreen == AppScreen.CALENDAR) FontWeight.Bold else FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = glass.primaryAccent,
                    selectedTextColor = glass.primaryAccent,
                    indicatorColor = glass.primaryAccentSoftBg,
                    unselectedIconColor = glass.textMuted,
                    unselectedTextColor = glass.textMuted
                ),
                modifier = Modifier.testTag("nav_calendar")
            )

            NavigationBarItem(
                selected = currentScreen == AppScreen.TIMETABLE,
                onClick = { onNavigate(AppScreen.TIMETABLE) },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = if (isHebrew) "מערכת" else "Timetable"
                    )
                },
                label = {
                    Text(
                        text = if (isHebrew) "מערכת" else "Timetable",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (currentScreen == AppScreen.TIMETABLE) FontWeight.Bold else FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = glass.primaryAccent,
                    selectedTextColor = glass.primaryAccent,
                    indicatorColor = glass.primaryAccentSoftBg,
                    unselectedIconColor = glass.textMuted,
                    unselectedTextColor = glass.textMuted
                ),
                modifier = Modifier.testTag("nav_timetable")
            )

            NavigationBarItem(
                selected = currentScreen == AppScreen.ALARMS,
                onClick = { onNavigate(AppScreen.ALARMS) },
                icon = {
                    Icon(
                        imageVector = Icons.Default.AccessAlarm,
                        contentDescription = if (isHebrew) "שעונים" else "Alarms"
                    )
                },
                label = {
                    Text(
                        text = if (isHebrew) "שעונים" else "Alarms",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (currentScreen == AppScreen.ALARMS) FontWeight.Bold else FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = glass.primaryAccent,
                    selectedTextColor = glass.primaryAccent,
                    indicatorColor = glass.primaryAccentSoftBg,
                    unselectedIconColor = glass.textMuted,
                    unselectedTextColor = glass.textMuted
                ),
                modifier = Modifier.testTag("nav_alarms")
            )

            NavigationBarItem(
                selected = currentScreen == AppScreen.FILES,
                onClick = { onNavigate(AppScreen.FILES) },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = if (isHebrew) "קבצים" else "Files"
                    )
                },
                label = {
                    Text(
                        text = if (isHebrew) "קבצים" else "Files",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (currentScreen == AppScreen.FILES) FontWeight.Bold else FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = glass.primaryAccent,
                    selectedTextColor = glass.primaryAccent,
                    indicatorColor = glass.primaryAccentSoftBg,
                    unselectedIconColor = glass.textMuted,
                    unselectedTextColor = glass.textMuted
                ),
                modifier = Modifier.testTag("nav_files")
            )

            NavigationBarItem(
                selected = currentScreen == AppScreen.ADMIN_DASHBOARD,
                onClick = { onNavigate(AppScreen.ADMIN_DASHBOARD) },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Group,
                        contentDescription = if (isHebrew) {
                            if (isParent) "משפחה" else "המשפחה"
                        } else {
                            "Family"
                        }
                    )
                },
                label = {
                    Text(
                        text = if (isHebrew) {
                            if (isParent) "משפחה" else "המשפחה"
                        } else {
                            "Family"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (currentScreen == AppScreen.ADMIN_DASHBOARD) FontWeight.Bold else FontWeight.Medium
                        ),
                        maxLines = 1
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = glass.primaryAccent,
                    selectedTextColor = glass.primaryAccent,
                    indicatorColor = glass.primaryAccentSoftBg,
                    unselectedIconColor = glass.textMuted,
                    unselectedTextColor = glass.textMuted
                ),
                modifier = Modifier.testTag("nav_admin")
            )
        }
    }
}

@Composable
fun SoftGlassScreenBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val glass = LocalSoftGlassColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        glass.screenGradientTop,
                        glass.screenBackground,
                        glass.screenGradientBottom
                    )
                )
            ),
        content = content
    )
}
