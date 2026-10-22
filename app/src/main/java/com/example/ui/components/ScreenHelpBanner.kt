package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun ScreenHelpBanner(
    title: String,
    tips: List<String>,
    icon: ImageVector = Icons.Default.Lightbulb,
    isHebrew: Boolean = true,
    initiallyExpanded: Boolean = false,
    testTag: String = "screen_help_banner"
) {
    val glass = LocalSoftGlassColors.current
    var isExpanded by remember { mutableStateOf(initiallyExpanded) }

    FamilyGlassCard(
        cornerRadius = 20.dp,
        isTintedBlue = true,
        elevation = 2.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(testTag)
    ) {
        // Header Row (Clickable to toggle)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = glass.primaryAccentSoftBg,
                    border = BorderStroke(1.dp, glass.primaryAccent.copy(alpha = 0.3f)),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = glass.primaryAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = glass.primaryAccent
                        )
                    )
                    if (!isExpanded) {
                        Text(
                            text = if (isHebrew) "לחצו כאן להסבר וטיפים מהירים לשימוש במסך זה" else "Tap here for quick guide & tips",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = glass.textSecondary,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }

            FamilyStatusChip(
                label = if (isExpanded) {
                    if (isHebrew) "סגור ✕" else "Hide ✕"
                } else {
                    if (isHebrew) "💡 עזרה" else "💡 Tips"
                },
                containerColor = glass.cardBackground,
                contentColor = glass.primaryAccent,
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            )
        }

        // Expanded content
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                HorizontalDivider(color = glass.primaryAccent.copy(alpha = 0.2f), thickness = 0.8.dp)
                Spacer(modifier = Modifier.height(8.dp))

                tips.forEach { tip ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "•",
                            color = glass.primaryAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = tip,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = glass.textPrimary,
                                lineHeight = 18.sp
                            )
                        )
                    }
                }
            }
        }
    }
}
