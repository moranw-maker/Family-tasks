package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppNotification
import com.example.model.NotificationType
import com.example.ui.theme.*
import kotlinx.coroutines.delay

const val IN_APP_BANNER_AUTO_DISMISS_MS = 4_000L

@Composable
fun InAppNotificationBanner(
    alert: AppNotification?,
    onDismiss: () -> Unit,
    onBannerClick: ((AppNotification) -> Unit)? = null,
    autoDismissDurationMs: Long = IN_APP_BANNER_AUTO_DISMISS_MS
) {
    LaunchedEffect(alert?.id, autoDismissDurationMs) {
        if (alert != null && autoDismissDurationMs > 0L) {
            delay(autoDismissDurationMs)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = alert != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("in_app_notification_banner")
    ) {
        if (alert != null) {
            val (iconEmoji, accentColor, containerColor) = when (alert.type) {
                NotificationType.TASK_COMPLETED -> Triple("🎉", GoogleGreen, GoogleGreenLight)
                NotificationType.TASK_ASSIGNED -> Triple("📋", GoogleBlue, GoogleBlueLight)
                NotificationType.NUDGE_REMINDER -> Triple("⏰", GoogleRed, GoogleRedLight)
                NotificationType.SCHOOL_START -> Triple("🔔", GoogleYellowDark, GoogleYellowLight)
                NotificationType.FILE_SHARED -> Triple("📁", GooglePurple, GooglePurpleLight)
                NotificationType.MEMBER_INVITED -> Triple("👋", GoogleBlue, GoogleBlueLight)
            }

            // Floating heads-up notification card (חלון צף)
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = containerColor,
                tonalElevation = 8.dp,
                shadowElevation = 10.dp,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, accentColor.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("in_app_notification_banner_card")
                    .clickable {
                        onBannerClick?.invoke(alert)
                        onDismiss()
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = alert.completedByAvatar ?: iconEmoji, fontSize = 22.sp)
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = accentColor
                                ) {
                                    Text(
                                        text = "PUSH",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }

                                Text(
                                    text = alert.title,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = accentColor
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = alert.message,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("dismiss_banner_alert_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Detailed completion breakdown if task completed
                    if (alert.type == NotificationType.TASK_COMPLETED && (alert.completedByMemberName != null || alert.taskTitle != null)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(text = "👤", fontSize = 12.sp)
                                    Text(
                                        text = "בוצע ע״י: ${alert.completedByMemberName ?: "חבר בית"}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = GoogleGreenDark
                                        )
                                    )
                                }

                                if (alert.pointsGained != null) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = GoogleYellowLight
                                    ) {
                                        Text(
                                            text = "+${alert.pointsGained} ⭐",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = GoogleYellowDark
                                            ),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
