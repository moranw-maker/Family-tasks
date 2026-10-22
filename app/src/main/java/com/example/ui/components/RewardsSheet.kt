package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.FamilyMember
import com.example.model.RewardItem
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewardsSheet(
    currentMember: FamilyMember,
    isHebrew: Boolean,
    onClaimReward: (RewardItem) -> Unit,
    onDismiss: () -> Unit
) {
    val glassColors = LocalSoftGlassColors.current
    var selectedFilter by remember { mutableIntStateOf(0) } // 0 = All, 1 = Available

    // Preset catalog of family rewards
    val catalogRewards = remember(isHebrew) {
        listOf(
            RewardItem(
                id = "rew_1",
                familyId = "fam",
                title = if (isHebrew) "משחק חדש לקונסולה" else "New Video Game",
                pointsCost = 500,
                iconEmoji = "🎮",
                description = if (isHebrew) "בחירת משחק חדש לחופשה" else "Choose a new game"
            ),
            RewardItem(
                id = "rew_2",
                familyId = "fam",
                title = if (isHebrew) "ערב פיצה משפחתית" else "Family Pizza Night",
                pointsCost = 800,
                iconEmoji = "🍕",
                description = if (isHebrew) "פיצה ומאפים לכל המשפחה" else "Pizza night for all"
            ),
            RewardItem(
                id = "rew_3",
                familyId = "fam",
                title = if (isHebrew) "כרטיס לסרט בקולנוע" else "Cinema Movie Ticket",
                pointsCost = 1000,
                iconEmoji = "🎬",
                description = if (isHebrew) "סרט ופופקורן לבחירה" else "Movie + popcorn"
            ),
            RewardItem(
                id = "rew_4",
                familyId = "fam",
                title = if (isHebrew) "יום כיף בפארק מים" else "Water Park Day Out",
                pointsCost = 1500,
                iconEmoji = "🌳",
                description = if (isHebrew) "יציאה משפחתית מלאה" else "Full family outing"
            ),
            RewardItem(
                id = "rew_5",
                familyId = "fam",
                title = if (isHebrew) "גלידה משפחתית ענקית" else "Giant Ice Cream Treat",
                pointsCost = 300,
                iconEmoji = "🍦",
                description = if (isHebrew) "גלידה וקינוחים מתוקים" else "Sweet family desserts"
            ),
            RewardItem(
                id = "rew_6",
                familyId = "fam",
                title = if (isHebrew) "שעת מסך נוספת בסופ\"ש" else "Extra Weekend Game Time",
                pointsCost = 200,
                iconEmoji = "📱",
                description = if (isHebrew) "שעה נוספת של משחקים" else "1 extra hour of games"
            )
        )
    }

    val displayRewards = catalogRewards.filter { item ->
        when (selectedFilter) {
            1 -> currentMember.earnedPoints >= item.pointsCost
            else -> true
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = glassColors.screenBackground,
        modifier = Modifier.testTag("rewards_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = glassColors.cardBackground,
                    border = BorderStroke(1.dp, glassColors.cardBorder),
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onDismiss() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = glassColors.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Text(
                    text = if (isHebrew) "הפרסים והנקודות" else "Rewards & Points",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = glassColors.textPrimary
                    )
                )

                FamilyMemberAvatar(member = currentMember, size = 36.dp, isHighlighted = true)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Hero Points Soft Glass Card
            FamilyGlassCard(
                cornerRadius = 24.dp,
                accentColor = SoftLavender,
                elevation = 4.dp,
                contentPadding = PaddingValues(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rewards_hero_points_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "${currentMember.earnedPoints} ${if (isHebrew) "נקודות" else "Points"}",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = if (glassColors.isDark) Color(0xFFD8B4FE) else PastelPurpleDark
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isHebrew) "הנקודות שצברת מביצוע משימות ⭐" else "Your chore points balance ⭐",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = glassColors.textSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (glassColors.isDark) Color(0xFF422006) else Color(0xFFFEF3C7),
                        border = BorderStroke(1.2.dp, GoogleYellow.copy(alpha = 0.45f)),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = "⭐", fontSize = 28.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Filter Tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RewardFilterPill(
                    label = if (isHebrew) "כל הפרסים" else "All Rewards",
                    isSelected = selectedFilter == 0,
                    onClick = { selectedFilter = 0 }
                )
                RewardFilterPill(
                    label = if (isHebrew) "הזמינים לי" else "Available",
                    isSelected = selectedFilter == 1,
                    onClick = { selectedFilter = 1 }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Rewards List using FamilyGlassCard, FamilyStatusChip, and FamilyPrimaryButton
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(displayRewards) { item ->
                    val canAfford = currentMember.earnedPoints >= item.pointsCost

                    FamilyGlassCard(
                        cornerRadius = 20.dp,
                        accentColor = if (canAfford) GoogleGreen else null,
                        elevation = 2.dp,
                        contentPadding = PaddingValues(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reward_item_${item.id}")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (canAfford) glassColors.successBg else glassColors.primaryAccentSoftBg,
                                    border = BorderStroke(
                                        1.dp,
                                        if (canAfford) glassColors.successBorder else glassColors.cardBorder
                                    ),
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(text = item.iconEmoji, fontSize = 24.sp)
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = glassColors.textPrimary
                                        )
                                    )
                                    FamilyStatusChip(
                                        emoji = "⭐",
                                        label = "${item.pointsCost} ${if (isHebrew) "נקודות" else "pts"}",
                                        containerColor = if (canAfford) glassColors.successBg else glassColors.warningBg,
                                        contentColor = if (canAfford) glassColors.successText else glassColors.warningText
                                    )
                                }
                            }

                            if (canAfford) {
                                FamilyPrimaryButton(
                                    text = if (isHebrew) "ממש" else "Claim",
                                    onClick = { onClaimReward(item) },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .height(38.dp)
                                        .testTag("claim_reward_btn_${item.id}")
                                )
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = glassColors.secondaryContainer,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "Locked",
                                            tint = glassColors.textMuted,
                                            modifier = Modifier.size(18.dp)
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

@Composable
private fun RowScope.RewardFilterPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val glassColors = LocalSoftGlassColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) glassColors.primaryAccent else glassColors.cardBackground,
        border = BorderStroke(
            1.dp,
            if (isSelected) glassColors.primaryAccent else glassColors.cardBorder
        ),
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.padding(vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (isSelected) {
                    if (glassColors.isDark) Color(0xFF0B1120) else Color.White
                } else {
                    glassColors.textSecondary
                },
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp
            )
        }
    }
}
