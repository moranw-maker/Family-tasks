package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
    var selectedFilter by remember { mutableIntStateOf(0) } // 0 = All, 1 = Available, 2 = Mine

    // Preset catalog of family rewards
    val catalogRewards = remember {
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
        containerColor = MaterialTheme.colorScheme.surface,
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
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }

                Text(
                    text = if (isHebrew) "הפרסים והנקודות" else "Rewards & Points",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )

                Spacer(modifier = Modifier.size(40.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Hero Points Card (matches reference screen 6)
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = PastelPurpleBackground,
                border = BorderStroke(1.dp, PastelPurpleBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFFEDE4FF),
                                    Color(0xFFF7F3FF),
                                    Color(0xFFE5D7FD)
                                )
                            )
                        )
                        .padding(20.dp)
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
                                    color = PastelPurpleDark
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isHebrew) "הנקודות שצברת מביצוע משימות ⭐" else "Your chore points balance ⭐",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }

                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFFEF3C7),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = "⭐", fontSize = 28.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Filter Tabs (matches reference)
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

            // Rewards List
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(displayRewards) { item ->
                    val canAfford = currentMember.earnedPoints >= item.pointsCost

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (canAfford) PastelGreenBorder else OutlineLight),
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reward_item_${item.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
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
                                    color = if (canAfford) PastelGreenBackground else Color(0xFFF1F5F9),
                                    modifier = Modifier.size(46.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(text = item.iconEmoji, fontSize = 24.sp)
                                    }
                                }

                                Column {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                    )
                                    Text(
                                        text = "${item.pointsCost} ${if (isHebrew) "נקודות" else "pts"}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = if (canAfford) PastelGreenDark else TextSecondary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }
                            }

                            if (canAfford) {
                                Button(
                                    onClick = { onClaimReward(item) },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = VibrantActionBlue),
                                    modifier = Modifier.height(38.dp)
                                ) {
                                    Text(
                                        text = if (isHebrew) "ממש" else "Claim",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Locked",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
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
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) VibrantActionBlue else Color(0xFFF1F5F9),
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
                color = if (isSelected) Color.White else TextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
    }
}
