package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    Surface(
        color = glass.cardBackground,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(0.5.dp, glass.cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. Profile Avatar with Soft Glass Status Ring
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(currentMember.colorHex).copy(alpha = if (glass.isDark) 0.28f else 0.18f))
                            .border(1.5.dp, glass.primaryAccent.copy(alpha = 0.7f), CircleShape)
                            .clickable { onOpenProfile() }
                            .testTag("user_profile_avatar_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = currentMember.avatarEmoji,
                            fontSize = 20.sp
                        )
                    }
                }

                // 2. Center: Brand House Icon & "FamilyTasks" Logo
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenProfile() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = glass.primaryAccentSoftBg,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = "🏡", fontSize = 17.sp)
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "FamilyTasks",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = glass.textPrimary,
                                letterSpacing = 0.2.sp
                            )
                        )
                        Text(
                            text = familyTitle,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = glass.textSecondary,
                                fontWeight = FontWeight.Medium
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 3. Actions (Language Pill, Gemini AI, Notification Bell, Settings)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = glass.primaryAccentSoftBg,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onLanguageToggle() }
                            .testTag("language_toggle_button")
                    ) {
                        Text(
                            text = if (isHebrew) "עב" else "EN",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = glass.primaryAccent
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }

                    IconButton(
                        onClick = onOpenGemini,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("top_gemini_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Gemini AI",
                            tint = glass.primaryAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = onOpenNotifications,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("notification_bell_button")
                    ) {
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
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("top_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = if (isHebrew) "הגדרות" else "Settings",
                            tint = glass.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
