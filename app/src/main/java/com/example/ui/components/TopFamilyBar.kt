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
                Color(0xFF1E293B),
                Color(0xFF162032)
            )
        } else {
            listOf(
                Color(0xFFFFFFFF),
                Color(0xFFF3F8FF)
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
            brush = Brush.verticalGradient(
                colors = listOf(
                    glass.cardBorder,
                    glass.primaryAccent.copy(alpha = if (glass.isDark) 0.40f else 0.28f)
                )
            )
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("top_family_bar")
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerGradient)
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. Profile Avatar using Soft Glass FamilyMemberAvatar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FamilyMemberAvatar(
                        member = currentMember,
                        size = 42.dp,
                        isHighlighted = true,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onOpenProfile() }
                            .testTag("user_profile_avatar_button")
                    )
                }

                // 2. Center: Brand House Squircle & FamilyTasks Capsule
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = glass.cardTintedBlueBackground.copy(alpha = if (glass.isDark) 0.65f else 0.85f),
                    border = BorderStroke(1.dp, glass.primaryAccent.copy(alpha = 0.22f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpenProfile() }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(11.dp),
                            color = glass.primaryAccentSoftBg,
                            border = BorderStroke(0.8.dp, glass.primaryAccent.copy(alpha = 0.35f)),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = "🏡", fontSize = 16.sp)
                            }
                        }

                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = "FamilyTasks",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = glass.textPrimary,
                                    letterSpacing = 0.2.sp
                                )
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

                // 3. Soft Glass Action Pills (Language Pill, Gemini AI, Notification Bell, Settings)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = glass.primaryAccentSoftBg,
                        border = BorderStroke(1.dp, glass.primaryAccent.copy(alpha = 0.32f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onLanguageToggle() }
                            .testTag("language_toggle_button")
                    ) {
                        Text(
                            text = if (isHebrew) "עב" else "EN",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = glass.primaryAccent
                            ),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = glass.cardTintedBlueBackground,
                        border = BorderStroke(1.dp, glass.cardBorder),
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenGemini() }
                            .testTag("top_gemini_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Gemini AI",
                                tint = glass.primaryAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = glass.cardTintedBlueBackground,
                        border = BorderStroke(1.dp, glass.cardBorder),
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
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
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = glass.cardTintedBlueBackground,
                        border = BorderStroke(1.dp, glass.cardBorder),
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenSettings() }
                            .testTag("top_settings_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = if (isHebrew) "הגדרות" else "Settings",
                                tint = glass.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
