package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
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

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left / Start: Family branding & Google Sync Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenProfile() }
                ) {
                // Family house icon badge
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(GoogleBlueLight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "🏡", fontSize = 18.sp)
                }

                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = familyTitle,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Google Cloud Sync indicator dot
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (isDriveSyncActive) GoogleGreen else GoogleYellow)
                        )
                    }

                    Text(
                        text = if (isHebrew) "סונכרן עם Drive ✓" else "Drive Synced ✓",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = GoogleGreenDark,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp
                        ),
                        maxLines = 1
                    )
                }
            }

            // Right / End: Actions (Language toggle, Gemini, Notifications, Settings, Profile Avatar)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Language quick toggle
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onLanguageToggle() }
                        .testTag("language_toggle_button")
                ) {
                    Text(
                        text = if (isHebrew) "🇮🇱 עב" else "🇺🇸 EN",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = GoogleBlue,
                            fontSize = 11.sp
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }

                // Gemini AI button
                IconButton(
                    onClick = onOpenGemini,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("top_gemini_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Gemini AI",
                        tint = GoogleBlue,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Notification Bell
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
                                    containerColor = GoogleRed,
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }

                // Settings icon button
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("top_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = if (isHebrew) "הגדרות" else "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Profile Avatar Button
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(currentMember.colorHex).copy(alpha = 0.25f))
                        .clickable { onOpenProfile() }
                        .testTag("user_profile_avatar_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = currentMember.avatarEmoji,
                        fontSize = 17.sp
                    )
                }
            }
        }
    }
}
}
