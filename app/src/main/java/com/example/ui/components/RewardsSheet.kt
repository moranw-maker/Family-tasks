package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.FamilyMember
import com.example.model.RedemptionStatus
import com.example.model.RewardItem
import com.example.model.RewardRedemptionRequest
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewardsSheet(
    currentMember: FamilyMember,
    isHebrew: Boolean,
    onClaimReward: (RewardItem) -> Unit,
    onDismiss: () -> Unit,
    rewards: List<RewardItem> = emptyList(),
    redemptions: List<RewardRedemptionRequest> = emptyList(),
    onRequestRedemption: (RewardItem) -> Unit = onClaimReward,
    onParentDecideRedemption: (redemptionId: String, approve: Boolean) -> Unit = { _, _ -> },
    onCreateReward: (title: String, pointsCost: Int, iconEmoji: String, description: String) -> Unit = { _, _, _, _ -> },
    onUpdateReward: (rewardId: String, title: String, pointsCost: Int, iconEmoji: String, description: String, isActive: Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onToggleRewardActive: (rewardId: String, isActive: Boolean) -> Unit = { _, _ -> },
    onDeleteReward: (rewardId: String) -> Unit = {}
) {
    val glassColors = LocalSoftGlassColors.current
    var selectedFilter by remember { mutableIntStateOf(0) } // 0 = All, 1 = Available, 2 = Redemptions
    var showCreateRewardDialog by remember { mutableStateOf(false) }
    var rewardToEdit by remember { mutableStateOf<RewardItem?>(null) }

    // Default fallback catalog if repository list is empty
    val fallbackCatalog = remember(isHebrew) {
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

    val effectiveCatalog = remember(rewards, fallbackCatalog, currentMember.isParent) {
        val base = if (rewards.isNotEmpty()) rewards else fallbackCatalog
        base.filter { item ->
            !item.isDeleted && (currentMember.isParent || item.isActive)
        }
    }

    val displayRewards = remember(effectiveCatalog, selectedFilter, currentMember.earnedPoints) {
        effectiveCatalog.filter { item ->
            when (selectedFilter) {
                1 -> item.isActive && currentMember.earnedPoints >= item.pointsCost
                else -> true
            }
        }
    }

    val visibleRedemptions = remember(redemptions, currentMember.id, currentMember.isParent) {
        if (currentMember.isParent) {
            redemptions.sortedByDescending { it.requestedAtMillis }
        } else {
            redemptions
                .filter { it.requestedByMemberId == currentMember.id }
                .sortedByDescending { it.requestedAtMillis }
        }
    }

    val pendingRedemptionsCount = remember(visibleRedemptions) {
        visibleRedemptions.count { it.status == RedemptionStatus.PENDING_PARENT_APPROVAL }
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
                    text = if (isHebrew) "קטלוג הפרסים ומימוש נקודות 🎁" else "Rewards Catalog & Redemptions 🎁",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = glassColors.textPrimary
                    )
                )

                FamilyMemberAvatar(member = currentMember, size = 36.dp, isHighlighted = true)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Hero Points Soft Glass Card + Parent Add Reward Button
            FamilyGlassCard(
                cornerRadius = 24.dp,
                accentColor = SoftLavender,
                elevation = 4.dp,
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rewards_hero_points_card")
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${currentMember.earnedPoints} ${if (isHebrew) "נקודות" else "Points"}",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (glassColors.isDark) Color(0xFFD8B4FE) else PastelPurpleDark
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (currentMember.isParent) {
                                    if (isHebrew) "מצב הורה: ניהול קטלוג פרסים ואישור בקשות מימוש" else "Parent mode: Manage catalog & approve redemptions"
                                } else {
                                    if (isHebrew) "הנקודות שצברת מביצוע משימות • מימוש באישור הורה ⭐" else "Your chore points • Redemptions require parent approval ⭐"
                                },
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
                            modifier = Modifier.size(50.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = "⭐", fontSize = 24.sp)
                            }
                        }
                    }

                    if (currentMember.isParent) {
                        FamilyPrimaryButton(
                            text = if (isHebrew) "➕ הוסף פרס חדש לקטלוג" else "➕ Add New Reward",
                            onClick = { showCreateRewardDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("add_new_reward_catalog_btn")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Filter Tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RewardFilterPill(
                    label = if (isHebrew) "כל הפרסים" else "All Rewards",
                    isSelected = selectedFilter == 0,
                    onClick = { selectedFilter = 0 },
                    testTag = "rewards_tab_all"
                )
                RewardFilterPill(
                    label = if (isHebrew) "הזמינים לי" else "Available",
                    isSelected = selectedFilter == 1,
                    onClick = { selectedFilter = 1 },
                    testTag = "rewards_tab_available"
                )
                RewardFilterPill(
                    label = if (isHebrew) {
                        if (pendingRedemptionsCount > 0) "בקשות מימוש ($pendingRedemptionsCount)" else "בקשות מימוש"
                    } else {
                        if (pendingRedemptionsCount > 0) "Requests ($pendingRedemptionsCount)" else "Requests"
                    },
                    isSelected = selectedFilter == 2,
                    onClick = { selectedFilter = 2 },
                    testTag = "rewards_tab_redemptions"
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                // Pending Redemptions Banner on main tabs if any pending
                if (selectedFilter != 2 && pendingRedemptionsCount > 0) {
                    item(key = "pending_redemptions_inline_section") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (isHebrew) {
                                    "⏳ בקשות מימוש פרסים הממתינות לאישור הורה ($pendingRedemptionsCount)"
                                } else {
                                    "⏳ Pending Reward Redemption Requests ($pendingRedemptionsCount)"
                                },
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = glassColors.primaryAccent
                                )
                            )
                            visibleRedemptions
                                .filter { it.status == RedemptionStatus.PENDING_PARENT_APPROVAL }
                                .forEach { req ->
                                    RedemptionRequestItemCard(
                                        req = req,
                                        isParent = currentMember.isParent,
                                        isHebrew = isHebrew,
                                        onApprove = { onParentDecideRedemption(req.id, true) },
                                        onReject = { onParentDecideRedemption(req.id, false) }
                                    )
                                }
                        }
                    }
                }

                if (selectedFilter == 2) {
                    if (visibleRedemptions.isEmpty()) {
                        item(key = "empty_redemptions") {
                            FamilyGlassCard(
                                cornerRadius = 20.dp,
                                elevation = 2.dp,
                                contentPadding = PaddingValues(20.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (isHebrew) {
                                        "אין כרגע בקשות מימוש פרסים."
                                    } else {
                                        "No reward redemption requests yet."
                                    },
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = glassColors.textSecondary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                    } else {
                        items(visibleRedemptions, key = { "redemption_${it.id}" }) { req ->
                            RedemptionRequestItemCard(
                                req = req,
                                isParent = currentMember.isParent,
                                isHebrew = isHebrew,
                                onApprove = { onParentDecideRedemption(req.id, true) },
                                onReject = { onParentDecideRedemption(req.id, false) }
                            )
                        }
                    }
                } else {
                    items(displayRewards, key = { "reward_${it.id}" }) { item ->
                        val canAfford = currentMember.earnedPoints >= item.pointsCost
                        val hasPendingForThisReward = visibleRedemptions.any {
                            it.rewardId == item.id &&
                                it.requestedByMemberId == currentMember.id &&
                                it.status == RedemptionStatus.PENDING_PARENT_APPROVAL
                        }

                        FamilyGlassCard(
                            cornerRadius = 20.dp,
                            accentColor = when {
                                !item.isActive -> glassColors.textMuted
                                canAfford -> GoogleGreen
                                else -> null
                            },
                            elevation = 2.dp,
                            contentPadding = PaddingValues(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("reward_item_${item.id}")
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                            color = if (canAfford && item.isActive) glassColors.successBg else glassColors.primaryAccentSoftBg,
                                            border = BorderStroke(
                                                1.dp,
                                                if (canAfford && item.isActive) glassColors.successBorder else glassColors.cardBorder
                                            ),
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(text = item.iconEmoji, fontSize = 24.sp)
                                            }
                                        }

                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = item.title,
                                                    style = MaterialTheme.typography.bodyLarge.copy(
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = glassColors.textPrimary
                                                    ),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (!item.isActive) {
                                                    FamilyStatusChip(
                                                        label = if (isHebrew) "מוסתר" else "Inactive",
                                                        containerColor = glassColors.warningBg,
                                                        contentColor = glassColors.warningText
                                                    )
                                                }
                                            }
                                            if (item.description.isNotBlank()) {
                                                Text(
                                                    text = item.description,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        color = glassColors.textSecondary
                                                    ),
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            FamilyStatusChip(
                                                emoji = "⭐",
                                                label = "${item.pointsCost} ${if (isHebrew) "נקודות" else "pts"}",
                                                containerColor = if (canAfford) glassColors.successBg else glassColors.warningBg,
                                                contentColor = if (canAfford) glassColors.successText else glassColors.warningText
                                            )
                                        }
                                    }

                                    if (hasPendingForThisReward) {
                                        FamilyStatusChip(
                                            emoji = "⏳",
                                            label = if (isHebrew) "ממתין לאישור" else "Pending",
                                            containerColor = glassColors.warningBg,
                                            contentColor = glassColors.warningText
                                        )
                                    } else if (canAfford && item.isActive) {
                                        FamilyPrimaryButton(
                                            text = if (isHebrew) "🎁 בקש לממש" else "🎁 Redeem",
                                            onClick = { onRequestRedemption(item) },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
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

                                // Parent Catalog Management Row: Edit, Toggle Active, Delete
                                if (currentMember.isParent) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = { rewardToEdit = item },
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(34.dp)
                                                .testTag("edit_reward_btn_${item.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isHebrew) "ערוך" else "Edit",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        OutlinedButton(
                                            onClick = { onToggleRewardActive(item.id, !item.isActive) },
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(34.dp)
                                                .testTag("toggle_reward_active_btn_${item.id}")
                                        ) {
                                            Text(
                                                text = if (item.isActive) {
                                                    if (isHebrew) "השבת" else "Disable"
                                                } else {
                                                    if (isHebrew) "הפעל" else "Enable"
                                                },
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        OutlinedButton(
                                            onClick = { onDeleteReward(item.id) },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GoogleRed),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier
                                                .height(34.dp)
                                                .testTag("delete_reward_btn_${item.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = if (isHebrew) "מחק פרס" else "Delete Reward",
                                                modifier = Modifier.size(15.dp)
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

    if (showCreateRewardDialog) {
        EditRewardCatalogDialog(
            rewardToEdit = null,
            isHebrew = isHebrew,
            onDismiss = { showCreateRewardDialog = false },
            onSave = { title, pointsCost, iconEmoji, description, _ ->
                onCreateReward(title, pointsCost, iconEmoji, description)
                showCreateRewardDialog = false
            }
        )
    }

    rewardToEdit?.let { editing ->
        EditRewardCatalogDialog(
            rewardToEdit = editing,
            isHebrew = isHebrew,
            onDismiss = { rewardToEdit = null },
            onSave = { title, pointsCost, iconEmoji, description, isActive ->
                onUpdateReward(editing.id, title, pointsCost, iconEmoji, description, isActive)
                rewardToEdit = null
            }
        )
    }
}

@Composable
private fun RedemptionRequestItemCard(
    req: RewardRedemptionRequest,
    isParent: Boolean,
    isHebrew: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val statusColor = when (req.status) {
        RedemptionStatus.PENDING_PARENT_APPROVAL -> Color(0xFFD97706)
        RedemptionStatus.APPROVED -> GoogleGreen
        RedemptionStatus.REJECTED, RedemptionStatus.CANCELLED -> GoogleRed
    }
    val statusLabel = when (req.status) {
        RedemptionStatus.PENDING_PARENT_APPROVAL ->
            if (isHebrew) "ממתין לאישור הורה ⏳" else "Pending Parent Approval ⏳"
        RedemptionStatus.APPROVED ->
            if (isHebrew) "אושר ומומש ✓" else "Approved & Redeemed ✓"
        RedemptionStatus.REJECTED ->
            if (isHebrew) "נדחה ע״י הורה" else "Rejected by Parent"
        RedemptionStatus.CANCELLED ->
            if (isHebrew) "בוטל" else "Cancelled"
    }

    FamilyGlassCard(
        cornerRadius = 18.dp,
        elevation = 2.dp,
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("redemption_request_card_${req.id}")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = req.rewardIconEmoji, fontSize = 24.sp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${req.rewardTitle} (${req.pointsCost} ⭐)",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = glass.textPrimary
                            )
                        )
                        Text(
                            text = if (isHebrew) {
                                "מבקש/ת: ${req.requestedByMemberName}"
                            } else {
                                "Requested by: ${req.requestedByMemberName}"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = glass.textSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }

                FamilyStatusChip(
                    label = statusLabel,
                    containerColor = statusColor.copy(alpha = 0.14f),
                    contentColor = statusColor
                )
            }

            if (isParent && req.status == RedemptionStatus.PENDING_PARENT_APPROVAL) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onApprove,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GoogleGreen,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("parent_approve_redemption_${req.id}")
                    ) {
                        Text(
                            text = if (isHebrew) "אשר מימוש והפחת נקודות ✓" else "Approve & Deduct ✓",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp
                        )
                    }
                    OutlinedButton(
                        onClick = onReject,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GoogleRed),
                        modifier = Modifier.testTag("parent_reject_redemption_${req.id}")
                    ) {
                        Text(
                            text = if (isHebrew) "דחה" else "Reject",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditRewardCatalogDialog(
    rewardToEdit: RewardItem?,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, pointsCost: Int, iconEmoji: String, description: String, isActive: Boolean) -> Unit
) {
    val glass = LocalSoftGlassColors.current
    var titleText by remember(rewardToEdit) { mutableStateOf(rewardToEdit?.title.orEmpty()) }
    var costText by remember(rewardToEdit) {
        mutableStateOf(rewardToEdit?.pointsCost?.toString() ?: "250")
    }
    var emojiText by remember(rewardToEdit) {
        mutableStateOf(rewardToEdit?.iconEmoji ?: "🎁")
    }
    var descriptionText by remember(rewardToEdit) {
        mutableStateOf(rewardToEdit?.description.orEmpty())
    }
    var isActive by remember(rewardToEdit) {
        mutableStateOf(rewardToEdit?.isActive ?: true)
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    val emojiChoices = listOf("🎁", "🎮", "🍕", "🎬", "🍦", "📱", "🌳", "⚽", "🎨", "🎧", "🏆", "⭐")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glass.cardBackground,
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.testTag("edit_reward_catalog_dialog"),
        title = {
            Text(
                text = if (rewardToEdit == null) {
                    if (isHebrew) "➕ הוספת פרס חדש לקטלוג" else "➕ Add New Reward"
                } else {
                    if (isHebrew) "✏️ עריכת פרס בקטלוג" else "✏️ Edit Reward"
                },
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = glass.textPrimary
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    emojiChoices.forEach { emoji ->
                        FilterChip(
                            selected = emojiText == emoji,
                            onClick = { emojiText = emoji },
                            label = { Text(emoji, fontSize = 18.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = titleText,
                    onValueChange = {
                        titleText = it
                        validationError = null
                    },
                    label = { Text(if (isHebrew) "שם הפרס" else "Reward title") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reward_title_input")
                )

                OutlinedTextField(
                    value = costText,
                    onValueChange = {
                        costText = it.filter { ch -> ch.isDigit() }
                        validationError = null
                    },
                    label = { Text(if (isHebrew) "עלות בנקודות (חיובי)" else "Points cost") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reward_cost_input")
                )

                OutlinedTextField(
                    value = descriptionText,
                    onValueChange = { descriptionText = it },
                    label = { Text(if (isHebrew) "תיאור הפרס (אופציונלי)" else "Description (optional)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reward_description_input")
                )

                if (rewardToEdit != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isHebrew) "הפרס פעיל וגלוי לילדים" else "Active and visible to children",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = glass.textPrimary
                            )
                        )
                        Switch(
                            checked = isActive,
                            onCheckedChange = { isActive = it }
                        )
                    }
                }

                validationError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = GoogleRed,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        },
        confirmButton = {
            FamilyPrimaryButton(
                text = if (isHebrew) "שמור פרס" else "Save Reward",
                onClick = {
                    val cost = costText.trim().toIntOrNull()
                    when {
                        titleText.isBlank() -> {
                            validationError = if (isHebrew) "יש להזין שם לפרס" else "Enter a reward title"
                        }
                        cost == null || cost <= 0 -> {
                            validationError = if (isHebrew) "עלות הפרס חייבת להיות גדולה מ-0" else "Cost must be > 0"
                        }
                        else -> {
                            onSave(titleText.trim(), cost, emojiText, descriptionText.trim(), isActive)
                        }
                    }
                },
                modifier = Modifier.testTag("confirm_save_reward_btn")
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = if (isHebrew) "ביטול" else "Cancel",
                    color = glass.textSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

@Composable
private fun RowScope.RewardFilterPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String = ""
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
            .then(if (testTag.isNotBlank()) Modifier.testTag(testTag) else Modifier)
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
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
