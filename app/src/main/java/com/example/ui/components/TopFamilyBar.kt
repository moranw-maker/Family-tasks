package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.model.FamilyMember
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopFamilyBar(
    currentMember: FamilyMember,
    unreadNotificationsCount: Int,
    isDriveSyncActive: Boolean,
    currentLanguage: AppLanguage,
    familyTitle: String = if (currentLanguage == AppLanguage.HEBREW) "משפחת וקסלר" else "Wexler Family",
    onLanguageToggle: () -> Unit,
    onOpenGemini: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val isHebrew = currentLanguage == AppLanguage.HEBREW
    val glass = LocalSoftGlassColors.current
    val headerShape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
    val headerGradient = Brush.verticalGradient(
        colors = if (glass.isDark) {
            listOf(
                Color(0xFF172136),
                Color(0xFF101829)
            )
        } else {
            listOf(
                Color(0xFFFFFFFF),
                Color(0xFFF5F8FE)
            )
        }
    )

    Surface(
        shape = headerShape,
        color = Color.Transparent,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.horizontalGradient(
                colors = if (glass.isDark) {
                    listOf(
                        glass.cardBorder,
                        glass.primaryAccent.copy(alpha = 0.42f),
                        glass.velvetVioletAccent.copy(alpha = 0.36f)
                    )
                } else {
                    listOf(
                        glass.cardBorder,
                        glass.primaryAccent.copy(alpha = 0.28f)
                    )
                }
            )
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("top_family_bar")
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerGradient)
                    .statusBarsPadding()
            ) {
                val isCompactHeader = maxWidth < 390.dp
                val isVeryNarrowHeader = maxWidth < 345.dp
                val avatarSize = if (isCompactHeader) 38.dp else 42.dp
                val actionBtnSize = if (isCompactHeader) 34.dp else 36.dp
                val actionIconSize = if (isCompactHeader) 17.dp else 18.dp
                val actionSpacing = if (isCompactHeader) 4.dp else 5.dp

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = if (isCompactHeader) 10.dp else 14.dp,
                            vertical = if (isCompactHeader) 8.dp else 10.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 1. Profile Avatar
                    FamilyMemberAvatar(
                        member = currentMember,
                        size = avatarSize,
                        isHighlighted = true,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onOpenProfile() }
                            .testTag("user_profile_avatar_button")
                    )

                    // 2. Center: Brand House Squircle & FamilyTasks Capsule (Weighted so it never overlaps icons)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = if (isCompactHeader) 6.dp else 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = glass.cardTintedBlueBackground.copy(alpha = if (glass.isDark) 0.72f else 0.90f),
                            border = BorderStroke(
                                1.dp,
                                if (glass.isDark) glass.velvetVioletAccent.copy(alpha = 0.28f)
                                else glass.primaryAccent.copy(alpha = 0.22f)
                            ),
                            modifier = Modifier
                                .widthIn(max = 240.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onOpenProfile() }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(if (isCompactHeader) 6.dp else 8.dp),
                                modifier = Modifier.padding(
                                    horizontal = if (isCompactHeader) 8.dp else 10.dp,
                                    vertical = 5.dp
                                )
                            ) {
                                if (!isVeryNarrowHeader) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = glass.primaryAccentSoftBg,
                                        border = BorderStroke(0.8.dp, glass.primaryAccent.copy(alpha = 0.35f)),
                                        modifier = Modifier.size(if (isCompactHeader) 28.dp else 30.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "🏡",
                                                fontSize = if (isCompactHeader) 13.sp else 15.sp
                                            )
                                        }
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f, fill = false),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        text = "FamilyTasks",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = glass.textPrimary,
                                            letterSpacing = 0.2.sp
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = familyTitle,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = glass.primaryAccent,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // 3. Velvet Action Pills (Language Pill, Gemini AI, Notification Bell, Settings)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(actionSpacing)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(11.dp),
                            color = glass.primaryAccentSoftBg,
                            border = BorderStroke(1.dp, glass.primaryAccent.copy(alpha = 0.32f)),
                            modifier = Modifier
                                .height(actionBtnSize)
                                .clip(RoundedCornerShape(11.dp))
                                .clickable { onLanguageToggle() }
                                .testTag("language_toggle_button")
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = if (isCompactHeader) 7.dp else 9.dp)
                            ) {
                                Text(
                                    text = if (isHebrew) "עב" else "EN",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = glass.primaryAccent
                                    ),
                                    maxLines = 1
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(11.dp),
                            color = glass.cardTintedBlueBackground,
                            border = BorderStroke(1.dp, glass.cardBorder),
                            modifier = Modifier
                                .size(actionBtnSize)
                                .clip(RoundedCornerShape(11.dp))
                                .clickable { onOpenGemini() }
                                .testTag("top_gemini_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "Gemini AI",
                                    tint = if (glass.isDark) glass.velvetVioletAccent else glass.primaryAccent,
                                    modifier = Modifier.size(actionIconSize)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(11.dp),
                            color = glass.cardTintedBlueBackground,
                            border = BorderStroke(1.dp, glass.cardBorder),
                            modifier = Modifier
                                .size(actionBtnSize)
                                .clip(RoundedCornerShape(11.dp))
                                .clickable { onOpenNotifications() }
                                .testTag("notification_bell_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                BadgedBox(
                                    badge = {
                                        if (unreadNotificationsCount > 0) {
                                            Badge(
                                                containerColor = Color(0xFFEF4444),
                                                contentColor = Color.White
                                            ) {
                                                Text(
                                                    text = unreadNotificationsCount.toString(),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 9.sp
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Notifications,
                                        contentDescription = if (isHebrew) "התראות" else "Notifications",
                                        tint = glass.primaryAccent,
                                        modifier = Modifier.size(actionIconSize)
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(11.dp),
                            color = glass.cardTintedBlueBackground,
                            border = BorderStroke(1.dp, glass.cardBorder),
                            modifier = Modifier
                                .size(actionBtnSize)
                                .clip(RoundedCornerShape(11.dp))
                                .clickable { onOpenSettings() }
                                .testTag("top_settings_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = if (isHebrew) "הגדרות" else "Settings",
                                    tint = glass.textSecondary,
                                    modifier = Modifier.size(actionIconSize)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
