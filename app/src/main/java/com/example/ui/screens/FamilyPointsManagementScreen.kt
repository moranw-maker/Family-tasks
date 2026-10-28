package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.FamilyMember
import com.example.model.PointsActionType
import com.example.model.PointsHistoryEntry
import com.example.ui.components.FamilyGlassCard
import com.example.ui.components.FamilyMemberAvatar
import com.example.ui.components.FamilyPrimaryButton
import com.example.ui.components.FamilySecondaryButton
import com.example.ui.components.FamilySectionHeader
import com.example.ui.components.FamilyStatusChip
import com.example.ui.components.SoftGlassScreenBackground
import com.example.ui.components.velvetNeumorphicSheen
import com.example.ui.theme.GoogleGreen
import com.example.ui.theme.GoogleRed
import com.example.ui.theme.GoogleYellow
import com.example.ui.theme.LocalSoftGlassColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private enum class MemberPointsDialogMode {
    MANAGE_HUB,
    ADD,
    DEDUCT,
    RESET
}

@Composable
fun FamilyPointsManagementScreen(
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    pointsHistory: List<PointsHistoryEntry>,
    isProcessingPointsOperation: Boolean,
    pointsOperationError: String?,
    isHebrew: Boolean,
    onAddPoints: (memberId: String, amount: Int, reason: String, operationId: String) -> Boolean,
    onDeductPoints: (memberId: String, amount: Int, reason: String, operationId: String) -> Boolean,
    onResetPoints: (memberId: String, reason: String, operationId: String) -> Boolean,
    onClearError: () -> Unit,
    onNavigateBack: () -> Unit,
    onTransferPoints: (fromMemberId: String, toMemberId: String, amount: Int, reason: String) -> Boolean = { _, _, _, _ -> false },
    onGrantHelpingBonus: (memberId: String, amount: Int, reason: String) -> Boolean = { _, _, _ -> false }
) {
    BackHandler { onNavigateBack() }

    val glass = LocalSoftGlassColors.current
    var activeDialogMemberId by remember { mutableStateOf<String?>(null) }
    var activeDialogMode by remember { mutableStateOf<MemberPointsDialogMode?>(null) }
    var showTransferPointsDialog by remember { mutableStateOf(false) }
    var showHelpingBonusDialog by remember { mutableStateOf(false) }
    var selectedHistoryMemberFilter by remember { mutableStateOf<String?>(null) }
    var selectedActionTypeFilter by remember { mutableStateOf<PointsActionType?>(null) }

    val activeDialogMember = remember(members, activeDialogMemberId) {
        members.find { it.id == activeDialogMemberId }
    }

    // Security Guard: Only parents can access Family Points Management
    if (!currentMember.isParent) {
        SoftGlassScreenBackground(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .testTag("points_management_unauthorized_screen"),
                contentAlignment = Alignment.Center
            ) {
                FamilyGlassCard(
                    cornerRadius = 26.dp,
                    elevation = 4.dp,
                    contentPadding = PaddingValues(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = GoogleRed,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = if (isHebrew) "גישה להורים בלבד 🔒" else "Parents Only 🔒",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = glass.textPrimary
                            )
                        )
                        Text(
                            text = if (isHebrew) {
                                "מערכת ניהול הנקודות המשפחתית פתוחה להורים בלבד."
                            } else {
                                "The Family Points Management system is restricted to parents."
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = glass.textSecondary
                            ),
                            textAlign = TextAlign.Center
                        )
                        FamilyPrimaryButton(
                            text = if (isHebrew) "חזרה" else "Back",
                            leadingIcon = Icons.AutoMirrored.Filled.ArrowBack,
                            onClick = onNavigateBack,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("points_unauthorized_back_btn")
                        )
                    }
                }
            }
        }
        return
    }

    val sortedMembers = remember(members) {
        members.sortedWith(compareBy<FamilyMember> { it.isParent }.thenByDescending { it.earnedPoints })
    }
    val totalFamilyPoints = remember(members) {
        members.sumOf { it.earnedPoints.coerceAtLeast(0) }
    }

    val filteredHistory = remember(pointsHistory, selectedHistoryMemberFilter, selectedActionTypeFilter) {
        pointsHistory
            .asSequence()
            .filter { selectedHistoryMemberFilter == null || it.memberId == selectedHistoryMemberFilter }
            .filter { selectedActionTypeFilter == null || it.actionType == selectedActionTypeFilter }
            .sortedByDescending { it.timestampMillis }
            .toList()
    }

    SoftGlassScreenBackground(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("family_points_management_screen"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Velvet Header Card with Back Navigation & Summary
            item(key = "points_management_header") {
                FamilyGlassCard(
                    cornerRadius = 26.dp,
                    isTintedBlue = true,
                    elevation = 4.dp,
                    contentPadding = PaddingValues(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = glass.primaryAccentSoftBg,
                                    border = BorderStroke(1.dp, glass.primaryAccent.copy(alpha = 0.35f)),
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { onNavigateBack() }
                                        .testTag("points_management_back_btn")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = if (isHebrew) "חזרה להגדרות" else "Back to Settings",
                                            tint = glass.primaryAccent,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isHebrew) "ניהול נקודות משפחתי ⭐" else "Family Points Management ⭐",
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = glass.textPrimary
                                        ),
                                        modifier = Modifier.testTag("points_management_title")
                                    )
                                    Text(
                                        text = if (isHebrew) {
                                            "הוספה, הפחתה, איפוס והיסטוריית נקודות בזמן אמת • הרשאת הורה"
                                        } else {
                                            "Real-time points addition, deduction, reset & audit log • Parent Only"
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = glass.textSecondary
                                        )
                                    )
                                }
                            }

                            FamilyStatusChip(
                                label = if (isHebrew) "סה״כ: $totalFamilyPoints ⭐" else "Total: $totalFamilyPoints ⭐",
                                containerColor = glass.primaryAccentSoftBg,
                                contentColor = glass.primaryAccent
                            )
                        }

                        if (!pointsOperationError.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = glass.dangerBg,
                                border = BorderStroke(1.dp, glass.dangerText.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("points_operation_error_banner")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = pointsOperationError,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = glass.dangerText,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = onClearError,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = if (isHebrew) "סגור הודעה" else "Dismiss",
                                            tint = glass.dangerText,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                        // Parent Quick Economy Actions: Transfer Points Between Children & Grant Helping Bonus
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FamilyPrimaryButton(
                                text = if (isHebrew) "🔄 העברת נקודות בין ילדים" else "🔄 Transfer Points",
                                enabled = !isProcessingPointsOperation && members.size >= 2,
                                onClick = {
                                    onClearError()
                                    showTransferPointsDialog = true
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("transfer_points_between_children_btn")
                            )

                            FamilySecondaryButton(
                                text = if (isHebrew) "🌟 בונוס עזרה" else "🌟 Helping Bonus",
                                enabled = !isProcessingPointsOperation && members.isNotEmpty(),
                                onClick = {
                                    onClearError()
                                    showHelpingBonusDialog = true
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("grant_helping_bonus_btn")
                            )
                        }
                    }
                }
            }

            // 2. Family Members Section Header
            item(key = "points_members_section_header") {
                FamilySectionHeader(
                    title = if (isHebrew) "חשבונות נקודות של בני המשפחה" else "Family Member Points Accounts",
                    subtitle = if (isHebrew) {
                        "בחרו בן משפחה להוספה, הפחתה, איפוס או צפייה בהיסטוריית השינויים"
                    } else {
                        "Select a family member to add, deduct, reset points or view history"
                    }
                )
            }

            // 3. Family Member Cards
            items(sortedMembers, key = { "points_member_${it.id}" }) { member ->
                val memberHistoryCount = remember(pointsHistory, member.id) {
                    pointsHistory.count { it.memberId == member.id }
                }
                val isHistoryFilteredToThisMember = selectedHistoryMemberFilter == member.id

                FamilyMemberPointsCard(
                    member = member,
                    historyCount = memberHistoryCount,
                    isHistorySelected = isHistoryFilteredToThisMember,
                    isProcessing = isProcessingPointsOperation,
                    isHebrew = isHebrew,
                    onOpenManageHub = {
                        onClearError()
                        activeDialogMemberId = member.id
                        activeDialogMode = MemberPointsDialogMode.MANAGE_HUB
                    },
                    onOpenAddPoints = {
                        onClearError()
                        activeDialogMemberId = member.id
                        activeDialogMode = MemberPointsDialogMode.ADD
                    },
                    onOpenDeductPoints = {
                        onClearError()
                        activeDialogMemberId = member.id
                        activeDialogMode = MemberPointsDialogMode.DEDUCT
                    },
                    onOpenResetPoints = {
                        onClearError()
                        activeDialogMemberId = member.id
                        activeDialogMode = MemberPointsDialogMode.RESET
                    },
                    onToggleMemberHistory = {
                        selectedHistoryMemberFilter = if (selectedHistoryMemberFilter == member.id) {
                            null
                        } else {
                            member.id
                        }
                    }
                )
            }

            // 4. Points History Section ("היסטוריית נקודות")
            item(key = "points_history_section_header") {
                val filterMemberName = remember(members, selectedHistoryMemberFilter) {
                    members.find { it.id == selectedHistoryMemberFilter }?.name
                }
                FamilySectionHeader(
                    title = if (isHebrew) {
                        if (filterMemberName != null) "היסטוריית נקודות • $filterMemberName" else "היסטוריית שינויי נקודות"
                    } else {
                        if (filterMemberName != null) "Points History • $filterMemberName" else "Points Change History"
                    },
                    subtitle = if (isHebrew) {
                        "תיעוד מלא של הוספות, הפחתות, איפוסים וצבירה ממשימות מהחדש לישן"
                    } else {
                        "Complete log of additions, deductions, resets & task points (newest first)"
                    }
                )
            }

            // 5. History Filters (By Action Type & Member)
            item(key = "points_history_filters") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PointsFilterChip(
                            label = if (isHebrew) "כל הפעולות" else "All Actions",
                            selected = selectedActionTypeFilter == null,
                            accentColor = glass.primaryAccent,
                            onClick = { selectedActionTypeFilter = null },
                            testTag = "points_history_filter_ALL"
                        )
                        PointsFilterChip(
                            label = if (isHebrew) "הוספה (+)" else "Added (+)",
                            selected = selectedActionTypeFilter == PointsActionType.ADD,
                            accentColor = GoogleGreen,
                            onClick = {
                                selectedActionTypeFilter =
                                    if (selectedActionTypeFilter == PointsActionType.ADD) null else PointsActionType.ADD
                            },
                            testTag = "points_history_filter_ADD"
                        )
                        PointsFilterChip(
                            label = if (isHebrew) "הפחתה (-)" else "Deducted (-)",
                            selected = selectedActionTypeFilter == PointsActionType.DEDUCT,
                            accentColor = GoogleRed,
                            onClick = {
                                selectedActionTypeFilter =
                                    if (selectedActionTypeFilter == PointsActionType.DEDUCT) null else PointsActionType.DEDUCT
                            },
                            testTag = "points_history_filter_DEDUCT"
                        )
                        PointsFilterChip(
                            label = if (isHebrew) "איפוס (0)" else "Reset (0)",
                            selected = selectedActionTypeFilter == PointsActionType.RESET,
                            accentColor = GoogleYellow,
                            onClick = {
                                selectedActionTypeFilter =
                                    if (selectedActionTypeFilter == PointsActionType.RESET) null else PointsActionType.RESET
                            },
                            testTag = "points_history_filter_RESET"
                        )
                        PointsFilterChip(
                            label = if (isHebrew) "צבירה ממשימה ✓" else "Task Earned ✓",
                            selected = selectedActionTypeFilter == PointsActionType.TASK_EARNED,
                            accentColor = GoogleGreen,
                            onClick = {
                                selectedActionTypeFilter =
                                    if (selectedActionTypeFilter == PointsActionType.TASK_EARNED) null else PointsActionType.TASK_EARNED
                            },
                            testTag = "points_history_filter_TASK_EARNED"
                        )
                        PointsFilterChip(
                            label = if (isHebrew) "חלוקת משימה 🤝" else "Split Task 🤝",
                            selected = selectedActionTypeFilter == PointsActionType.TASK_SPLIT_EARNED,
                            accentColor = GoogleGreen,
                            onClick = {
                                selectedActionTypeFilter =
                                    if (selectedActionTypeFilter == PointsActionType.TASK_SPLIT_EARNED) null else PointsActionType.TASK_SPLIT_EARNED
                            },
                            testTag = "points_history_filter_TASK_SPLIT_EARNED"
                        )
                        PointsFilterChip(
                            label = if (isHebrew) "העברה נכנסת 📥" else "Transfer In 📥",
                            selected = selectedActionTypeFilter == PointsActionType.TRANSFER_IN,
                            accentColor = glass.primaryAccent,
                            onClick = {
                                selectedActionTypeFilter =
                                    if (selectedActionTypeFilter == PointsActionType.TRANSFER_IN) null else PointsActionType.TRANSFER_IN
                            },
                            testTag = "points_history_filter_TRANSFER_IN"
                        )
                        PointsFilterChip(
                            label = if (isHebrew) "העברה יוצאת 📤" else "Transfer Out 📤",
                            selected = selectedActionTypeFilter == PointsActionType.TRANSFER_OUT,
                            accentColor = GoogleRed,
                            onClick = {
                                selectedActionTypeFilter =
                                    if (selectedActionTypeFilter == PointsActionType.TRANSFER_OUT) null else PointsActionType.TRANSFER_OUT
                            },
                            testTag = "points_history_filter_TRANSFER_OUT"
                        )
                        PointsFilterChip(
                            label = if (isHebrew) "בונוס עזרה 🌟" else "Helping Bonus 🌟",
                            selected = selectedActionTypeFilter == PointsActionType.HELPING_BONUS,
                            accentColor = Color(0xFFD97706),
                            onClick = {
                                selectedActionTypeFilter =
                                    if (selectedActionTypeFilter == PointsActionType.HELPING_BONUS) null else PointsActionType.HELPING_BONUS
                            },
                            testTag = "points_history_filter_HELPING_BONUS"
                        )
                        PointsFilterChip(
                            label = if (isHebrew) "מימוש פרס 🎁" else "Reward Redeemed 🎁",
                            selected = selectedActionTypeFilter == PointsActionType.REWARD_REDEEMED,
                            accentColor = Color(0xFF9333EA),
                            onClick = {
                                selectedActionTypeFilter =
                                    if (selectedActionTypeFilter == PointsActionType.REWARD_REDEEMED) null else PointsActionType.REWARD_REDEEMED
                            },
                            testTag = "points_history_filter_REWARD_REDEEMED"
                        )
                    }

                    if (selectedHistoryMemberFilter != null) {
                        val selectedMember = members.find { it.id == selectedHistoryMemberFilter }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AssistChip(
                                onClick = { selectedHistoryMemberFilter = null },
                                label = {
                                    Text(
                                        text = if (isHebrew) {
                                            "מציג היסטוריה של: ${selectedMember?.name.orEmpty()} (לחץ להצגת כולם)"
                                        } else {
                                            "Showing: ${selectedMember?.nameEn.orEmpty()} (Tap for all)"
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                },
                                modifier = Modifier.testTag("clear_member_history_filter_chip")
                            )
                        }
                    }
                }
            }

            // 6. History Entries List
            if (filteredHistory.isEmpty()) {
                item(key = "points_history_empty") {
                    FamilyGlassCard(
                        cornerRadius = 22.dp,
                        elevation = 2.dp,
                        contentPadding = PaddingValues(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("points_history_empty_card")
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = glass.textMuted,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = if (isHebrew) {
                                    "עדיין לא נרשמו שינויי נקודות בהיסטוריה"
                                } else {
                                    "No points changes recorded yet"
                                },
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = glass.textPrimary
                                ),
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = if (isHebrew) {
                                    "כל הוספה, הפחתה, איפוס או אישור משימה יתועדו כאן באופן אוטומטי מרגע זה ואילך."
                                } else {
                                    "Every addition, deduction, reset, or task approval will be automatically logged here."
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = glass.textSecondary
                                ),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(filteredHistory, key = { "pts_hist_row_${it.operationId.ifBlank { it.id }}" }) { entry ->
                    PointsHistoryItemCard(
                        entry = entry,
                        isHebrew = isHebrew
                    )
                }
            }
        }
    }

    // --- Dialogs for Manage Hub / Add / Deduct / Reset ---
    if (activeDialogMember != null && activeDialogMode != null) {
        when (activeDialogMode) {
            MemberPointsDialogMode.MANAGE_HUB -> {
                ManageMemberPointsHubDialog(
                    member = activeDialogMember,
                    isHebrew = isHebrew,
                    onSelectAdd = { activeDialogMode = MemberPointsDialogMode.ADD },
                    onSelectDeduct = { activeDialogMode = MemberPointsDialogMode.DEDUCT },
                    onSelectReset = { activeDialogMode = MemberPointsDialogMode.RESET },
                    onViewHistory = {
                        selectedHistoryMemberFilter = activeDialogMember.id
                        activeDialogMode = null
                        activeDialogMemberId = null
                    },
                    onDismiss = {
                        activeDialogMode = null
                        activeDialogMemberId = null
                    }
                )
            }

            MemberPointsDialogMode.ADD -> {
                AddMemberPointsDialog(
                    member = activeDialogMember,
                    isProcessing = isProcessingPointsOperation,
                    isHebrew = isHebrew,
                    onConfirmAdd = { amount, reason, opId ->
                        val ok = onAddPoints(activeDialogMember.id, amount, reason, opId)
                        if (ok) {
                            activeDialogMode = null
                            activeDialogMemberId = null
                        }
                    },
                    onDismiss = {
                        activeDialogMode = null
                        activeDialogMemberId = null
                    }
                )
            }

            MemberPointsDialogMode.DEDUCT -> {
                DeductMemberPointsDialog(
                    member = activeDialogMember,
                    isProcessing = isProcessingPointsOperation,
                    isHebrew = isHebrew,
                    onConfirmDeduct = { amount, reason, opId ->
                        val ok = onDeductPoints(activeDialogMember.id, amount, reason, opId)
                        if (ok) {
                            activeDialogMode = null
                            activeDialogMemberId = null
                        }
                    },
                    onDismiss = {
                        activeDialogMode = null
                        activeDialogMemberId = null
                    }
                )
            }

            MemberPointsDialogMode.RESET -> {
                ResetMemberPointsConfirmDialog(
                    member = activeDialogMember,
                    isProcessing = isProcessingPointsOperation,
                    isHebrew = isHebrew,
                    onConfirmReset = { reason, opId ->
                        val ok = onResetPoints(activeDialogMember.id, reason, opId)
                        if (ok) {
                            activeDialogMode = null
                            activeDialogMemberId = null
                        }
                    },
                    onDismiss = {
                        activeDialogMode = null
                        activeDialogMemberId = null
                    }
                )
            }

            null -> Unit
        }
    }

    if (showTransferPointsDialog) {
        TransferPointsBetweenMembersDialog(
            members = members,
            isProcessing = isProcessingPointsOperation,
            isHebrew = isHebrew,
            onConfirmTransfer = { fromId, toId, amount, reason ->
                val ok = onTransferPoints(fromId, toId, amount, reason)
                if (ok) {
                    showTransferPointsDialog = false
                }
            },
            onDismiss = { showTransferPointsDialog = false }
        )
    }

    if (showHelpingBonusDialog) {
        GrantHelpingBonusDialog(
            members = members,
            isProcessing = isProcessingPointsOperation,
            isHebrew = isHebrew,
            onConfirmBonus = { memberId, amount, reason ->
                val ok = onGrantHelpingBonus(memberId, amount, reason)
                if (ok) {
                    showHelpingBonusDialog = false
                }
            },
            onDismiss = { showHelpingBonusDialog = false }
        )
    }
}

@Composable
private fun FamilyMemberPointsCard(
    member: FamilyMember,
    historyCount: Int,
    isHistorySelected: Boolean,
    isProcessing: Boolean,
    isHebrew: Boolean,
    onOpenManageHub: () -> Unit,
    onOpenAddPoints: () -> Unit,
    onOpenDeductPoints: () -> Unit,
    onOpenResetPoints: () -> Unit,
    onToggleMemberHistory: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val memberDisplayName = member.getDisplayName(isHebrew)

    FamilyGlassCard(
        cornerRadius = 24.dp,
        elevation = 3.dp,
        isTintedBlue = isHistorySelected,
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("points_member_card_${member.id}")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    FamilyMemberAvatar(
                        member = member,
                        size = 48.dp,
                        isHighlighted = true
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = memberDisplayName,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = glass.textPrimary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("points_member_name_${member.id}")
                        )
                        Text(
                            text = member.getRoleWithIdentityLabel(isHebrew),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = glass.textSecondary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Current Points Balance Pill
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = glass.primaryAccentSoftBg,
                    border = BorderStroke(1.dp, glass.primaryAccent.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .velvetNeumorphicSheen(
                            isDark = glass.isDark,
                            cornerRadius = 16.dp
                        )
                        .testTag("points_member_balance_${member.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (isHebrew) "${member.earnedPoints} נקודות" else "${member.earnedPoints} pts",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = glass.primaryAccent
                            )
                        )
                    }
                }
            }

            // Primary Action Row: "ניהול נקודות" + "היסטוריית שינויים"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FamilyPrimaryButton(
                    text = if (isHebrew) "ניהול נקודות" else "Manage Points",
                    leadingIcon = Icons.Default.Stars,
                    enabled = !isProcessing,
                    onClick = onOpenManageHub,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("manage_points_btn_${member.id}")
                )

                FamilySecondaryButton(
                    text = if (isHebrew) {
                        if (historyCount > 0) "היסטוריית שינויים ($historyCount)" else "היסטוריית שינויים"
                    } else {
                        if (historyCount > 0) "History ($historyCount)" else "History"
                    },
                    leadingIcon = Icons.Default.History,
                    onClick = onToggleMemberHistory,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("view_member_history_btn_${member.id}")
                )
            }

            // Quick Action Chips: Add (+), Deduct (-), Reset (0)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickPointsActionButton(
                    label = if (isHebrew) "+ הוספה" else "+ Add",
                    tint = GoogleGreen,
                    bgColor = glass.successBg,
                    enabled = !isProcessing,
                    onClick = onOpenAddPoints,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("add_points_btn_${member.id}")
                )

                QuickPointsActionButton(
                    label = if (isHebrew) "- הפחתה" else "- Deduct",
                    tint = GoogleRed,
                    bgColor = glass.dangerBg,
                    enabled = !isProcessing,
                    onClick = onOpenDeductPoints,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("deduct_points_btn_${member.id}")
                )

                QuickPointsActionButton(
                    label = if (isHebrew) "איפוס (0)" else "Reset (0)",
                    tint = Color(0xFFE8710A),
                    bgColor = glass.warningBg,
                    enabled = !isProcessing,
                    onClick = onOpenResetPoints,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("reset_points_btn_${member.id}")
                )
            }
        }
    }
}

@Composable
private fun QuickPointsActionButton(
    label: String,
    tint: Color,
    bgColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
        modifier = modifier
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onClick() }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = tint
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PointsFilterChip(
    label: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    testTag: String
) {
    val glass = LocalSoftGlassColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) accentColor.copy(alpha = if (glass.isDark) 0.24f else 0.14f) else glass.cardBackground,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) accentColor else glass.cardBorder
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = if (selected) accentColor else glass.textSecondary
                )
            )
        }
    }
}

@Composable
private fun PointsHistoryItemCard(
    entry: PointsHistoryEntry,
    isHebrew: Boolean
) {
    val glass = LocalSoftGlassColors.current
    val badgeColor = when (entry.actionType) {
        PointsActionType.ADD,
        PointsActionType.TASK_EARNED,
        PointsActionType.TASK_SPLIT_EARNED,
        PointsActionType.HELPING_BONUS -> GoogleGreen
        PointsActionType.TRANSFER_IN -> glass.primaryAccent
        PointsActionType.DEDUCT,
        PointsActionType.TRANSFER_OUT -> GoogleRed
        PointsActionType.REWARD_REDEEMED -> Color(0xFF9333EA)
        PointsActionType.RESET -> Color(0xFFE8710A)
    }
    val badgeBgColor = when (entry.actionType) {
        PointsActionType.ADD,
        PointsActionType.TASK_EARNED,
        PointsActionType.TASK_SPLIT_EARNED,
        PointsActionType.HELPING_BONUS -> glass.successBg
        PointsActionType.TRANSFER_IN,
        PointsActionType.REWARD_REDEEMED -> glass.primaryAccentSoftBg
        PointsActionType.DEDUCT,
        PointsActionType.TRANSFER_OUT -> glass.dangerBg
        PointsActionType.RESET -> glass.warningBg
    }
    val deltaFormatted = when (entry.actionType) {
        PointsActionType.ADD,
        PointsActionType.TASK_EARNED,
        PointsActionType.TASK_SPLIT_EARNED,
        PointsActionType.TRANSFER_IN,
        PointsActionType.HELPING_BONUS -> "+${entry.pointsDelta}"
        PointsActionType.DEDUCT,
        PointsActionType.TRANSFER_OUT,
        PointsActionType.REWARD_REDEEMED -> "${entry.pointsDelta}"
        PointsActionType.RESET -> if (entry.pointsDelta == 0) "0" else "${entry.pointsDelta}"
    }
    val formattedDateTime = remember(entry.timestampMillis) {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(entry.timestampMillis))
    }

    FamilyGlassCard(
        cornerRadius = 20.dp,
        elevation = 2.dp,
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("points_history_entry_${entry.operationId.ifBlank { entry.id }}")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = badgeBgColor,
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "${entry.actionType.getDisplayName(isHebrew)} ($deltaFormatted)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = badgeColor
                            ),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }

                    Text(
                        text = entry.memberName,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = glass.textPrimary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = formattedDateTime,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = glass.textMuted
                    )
                )
            }

            // Reason / Note
            Text(
                text = entry.reason,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = glass.textPrimary
                )
            )

            // Extra Provenance Metadata (Performer / Related Member)
            if (!entry.performedTaskByMemberName.isNullOrBlank() || !entry.relatedMemberName.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    entry.performedTaskByMemberName?.takeIf { it.isNotBlank() }?.let { performerName ->
                        FamilyStatusChip(
                            label = if (isHebrew) "מבצע בפועל: $performerName" else "Performed by: $performerName",
                            containerColor = glass.primaryAccentSoftBg,
                            contentColor = glass.primaryAccent
                        )
                    }
                    entry.relatedMemberName?.takeIf { it.isNotBlank() }?.let { relName ->
                        FamilyStatusChip(
                            label = if (isHebrew) "משויך ל: $relName" else "Related: $relName",
                            containerColor = glass.secondaryContainer,
                            contentColor = glass.textSecondary
                        )
                    }
                }
            }

            // Balance Before -> After & Performed By Parent
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isHebrew) {
                        "יתרה לפני: ${entry.previousBalance} ← יתרה אחרי: ${entry.newBalance}"
                    } else {
                        "Balance: ${entry.previousBalance} → ${entry.newBalance}"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = glass.primaryAccent
                    )
                )

                Text(
                    text = if (isHebrew) {
                        "אושר ע״י: ${entry.performedByName}"
                    } else {
                        "By: ${entry.performedByName}"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = glass.textSecondary
                    )
                )
            }
        }
    }
}

@Composable
private fun ManageMemberPointsHubDialog(
    member: FamilyMember,
    isHebrew: Boolean,
    onSelectAdd: () -> Unit,
    onSelectDeduct: () -> Unit,
    onSelectReset: () -> Unit,
    onViewHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glass.cardBackground,
        shape = RoundedCornerShape(26.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FamilyMemberAvatar(member = member, size = 40.dp, isHighlighted = true)
                Column {
                    Text(
                        text = if (isHebrew) "ניהול נקודות • ${member.name}" else "Manage Points • ${member.nameEn}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = glass.textPrimary
                        )
                    )
                    Text(
                        text = if (isHebrew) {
                            "יתרה נוכחית: ${member.earnedPoints} נקודות ⭐"
                        } else {
                            "Current balance: ${member.earnedPoints} points ⭐"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = glass.primaryAccent
                        )
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FamilyPrimaryButton(
                    text = if (isHebrew) "הוספת נקודות (+)" else "Add Points (+)",
                    leadingIcon = Icons.Default.AddCircle,
                    onClick = onSelectAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hub_select_add_points_btn")
                )
                FamilySecondaryButton(
                    text = if (isHebrew) "הפחתת נקודות (-)" else "Deduct Points (-)",
                    leadingIcon = Icons.Default.RemoveCircleOutline,
                    onClick = onSelectDeduct,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hub_select_deduct_points_btn")
                )
                FamilySecondaryButton(
                    text = if (isHebrew) "איפוס נקודות ל-0" else "Reset Points to 0",
                    leadingIcon = Icons.Default.RestartAlt,
                    onClick = onSelectReset,
                    contentColor = Color(0xFFE8710A),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hub_select_reset_points_btn")
                )
                FamilySecondaryButton(
                    text = if (isHebrew) "צפייה בהיסטוריית השינויים" else "View Change History",
                    leadingIcon = Icons.Default.History,
                    onClick = onViewHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hub_select_view_history_btn")
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = if (isHebrew) "סגור" else "Close",
                    color = glass.textSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

@Composable
private fun AddMemberPointsDialog(
    member: FamilyMember,
    isProcessing: Boolean,
    isHebrew: Boolean,
    onConfirmAdd: (amount: Int, reason: String, operationId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    var amountText by remember { mutableStateOf("") }
    var reasonText by remember { mutableStateOf(if (isHebrew) "עזרה בבית" else "Helping at home") }
    var validationError by remember { mutableStateOf<String?>(null) }
    var submittedOnce by remember { mutableStateOf(false) }
    val dialogOperationId = remember {
        "pts_add_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    }

    val parsedAmount = amountText.trim().toIntOrNull()
    val previewAfter = member.earnedPoints + (parsedAmount?.takeIf { it > 0 } ?: 0)
    val presetReasons = if (isHebrew) {
        listOf("עזרה בבית", "התנהגות טובה", "השלמת משימה מיוחדת")
    } else {
        listOf("Helping at home", "Good behavior", "Special task completion")
    }

    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        containerColor = glass.cardBackground,
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.testTag("add_points_dialog"),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (isHebrew) "הוספת נקודות • ${member.name}" else "Add Points • ${member.nameEn}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = glass.textPrimary
                    )
                )
                Text(
                    text = if (isHebrew) {
                        "יתרה נוכחית: ${member.earnedPoints} נקודות"
                    } else {
                        "Current balance: ${member.earnedPoints} points"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = glass.primaryAccent
                    ),
                    modifier = Modifier.testTag("add_points_current_balance_label")
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Quick Amount Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(5, 10, 20, 50).forEach { preset ->
                        FilterChip(
                            selected = parsedAmount == preset,
                            onClick = {
                                amountText = preset.toString()
                                validationError = null
                            },
                            label = { Text("+$preset", fontWeight = FontWeight.Bold) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("add_points_preset_$preset")
                        )
                    }
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it.filter { ch -> ch.isDigit() }
                        validationError = null
                    },
                    label = {
                        Text(if (isHebrew) "מספר נקודות להוספה" else "Points to add")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_points_amount_input")
                )

                OutlinedTextField(
                    value = reasonText,
                    onValueChange = {
                        reasonText = it
                        validationError = null
                    },
                    label = {
                        Text(if (isHebrew) "סיבה (לדוגמה: עזרה בבית)" else "Reason")
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_points_reason_input")
                )

                // Preset reason chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presetReasons.forEach { preset ->
                        AssistChip(
                            onClick = { reasonText = preset },
                            label = {
                                Text(preset, style = MaterialTheme.typography.labelSmall)
                            }
                        )
                    }
                }

                if (parsedAmount != null && parsedAmount > 0) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = glass.successBg,
                        border = BorderStroke(1.dp, GoogleGreen.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isHebrew) {
                                "יתרה לפני: ${member.earnedPoints} ← יתרה חדשה: $previewAfter נקודות ⭐"
                            } else {
                                "Balance: ${member.earnedPoints} → $previewAfter points ⭐"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = GoogleGreen
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                validationError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = GoogleRed,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.testTag("add_points_validation_error")
                    )
                }
            }
        },
        confirmButton = {
            FamilyPrimaryButton(
                text = if (isHebrew) "אישור הוספת נקודות" else "Confirm Add Points",
                enabled = !isProcessing && !submittedOnce,
                onClick = {
                    val amt = amountText.trim().toIntOrNull()
                    val rsn = reasonText.trim()
                    when {
                        amt == null || amt <= 0 -> {
                            validationError = if (isHebrew) "נא להזין מספר נקודות שלם וחיובי" else "Enter a positive integer"
                        }
                        rsn.isBlank() -> {
                            validationError = if (isHebrew) "נא להזין סיבה להוספת הנקודות" else "Please enter a reason"
                        }
                        else -> {
                            submittedOnce = true
                            onConfirmAdd(amt, rsn, dialogOperationId)
                        }
                    }
                },
                modifier = Modifier.testTag("confirm_add_points_btn")
            )
        },
        dismissButton = {
            TextButton(
                enabled = !isProcessing,
                onClick = onDismiss
            ) {
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
private fun DeductMemberPointsDialog(
    member: FamilyMember,
    isProcessing: Boolean,
    isHebrew: Boolean,
    onConfirmDeduct: (amount: Int, reason: String, operationId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    var amountText by remember { mutableStateOf("") }
    var reasonText by remember { mutableStateOf(if (isHebrew) "מימוש הטבה / הפחתה" else "Points deduction") }
    var awaitingFinalConfirmation by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var submittedOnce by remember { mutableStateOf(false) }
    val dialogOperationId = remember {
        "pts_ded_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    }

    val parsedAmount = amountText.trim().toIntOrNull()
    val balanceBefore = member.earnedPoints
    val balanceAfter = if (parsedAmount != null && parsedAmount > 0) balanceBefore - parsedAmount else balanceBefore
    val wouldBeNegative = parsedAmount != null && parsedAmount > 0 && balanceAfter < 0

    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        containerColor = glass.cardBackground,
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.testTag("deduct_points_dialog"),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (isHebrew) "הפחתת נקודות • ${member.name}" else "Deduct Points • ${member.nameEn}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = glass.textPrimary
                    )
                )
                Text(
                    text = if (isHebrew) {
                        "יתרה נוכחית: $balanceBefore נקודות"
                    } else {
                        "Current balance: $balanceBefore points"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = glass.textSecondary
                    )
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it.filter { ch -> ch.isDigit() }
                        awaitingFinalConfirmation = false
                        validationError = null
                    },
                    label = {
                        Text(if (isHebrew) "מספר נקודות להפחתה" else "Points to deduct")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("deduct_points_amount_input")
                )

                OutlinedTextField(
                    value = reasonText,
                    onValueChange = {
                        reasonText = it
                        awaitingFinalConfirmation = false
                        validationError = null
                    },
                    label = {
                        Text(if (isHebrew) "סיבה להפחתה" else "Reason for deduction")
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("deduct_points_reason_input")
                )

                // Live Before & After Balance Preview
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (wouldBeNegative) glass.dangerBg else glass.warningBg,
                    border = BorderStroke(
                        1.dp,
                        if (wouldBeNegative) GoogleRed.copy(alpha = 0.5f) else Color(0xFFE8710A).copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("deduct_points_preview_box")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (isHebrew) {
                                "תצוגה מקדימה: יתרה לפני: $balanceBefore ← יתרה אחרי: ${balanceAfter.coerceAtLeast(0)}"
                            } else {
                                "Preview: Before: $balanceBefore → After: ${balanceAfter.coerceAtLeast(0)}"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = if (wouldBeNegative) GoogleRed else glass.textPrimary
                            )
                        )
                        if (wouldBeNegative) {
                            Text(
                                text = if (isHebrew) {
                                    "לא ניתן להפחית מעבר ליתרה הקיימת ($balanceBefore נקודות) — יתרה שלילית אינה מותרת."
                                } else {
                                    "Cannot deduct more than available balance ($balanceBefore points)."
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = GoogleRed,
                                    fontWeight = FontWeight.Bold
                                ),
                                modifier = Modifier.testTag("deduct_points_negative_error")
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = awaitingFinalConfirmation && !wouldBeNegative) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = glass.dangerBg,
                        border = BorderStroke(1.dp, GoogleRed.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("deduct_points_confirmation_step_banner")
                    ) {
                        Text(
                            text = if (isHebrew) {
                                "אישור סופי: האם להפחית ${parsedAmount ?: 0} נקודות מ-${member.name}? לחצו שוב על הכפתור לאישור."
                            } else {
                                "Final confirmation: Deduct ${parsedAmount ?: 0} points from ${member.nameEn}?"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = GoogleRed
                            ),
                            modifier = Modifier.padding(12.dp)
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
                text = if (awaitingFinalConfirmation) {
                    if (isHebrew) "אשר הפחתת נקודות" else "Confirm Deduction"
                } else {
                    if (isHebrew) "הפחת נקודות" else "Deduct Points"
                },
                enabled = !isProcessing && !submittedOnce && !wouldBeNegative,
                onClick = {
                    val amt = amountText.trim().toIntOrNull()
                    val rsn = reasonText.trim()
                    when {
                        amt == null || amt <= 0 -> {
                            validationError = if (isHebrew) "נא להזין מספר נקודות שלם וחיובי" else "Enter a positive integer"
                        }
                        amt > balanceBefore -> {
                            validationError = if (isHebrew) "לא ניתן להפחית יותר מהיתרה הקיימת" else "Insufficient points balance"
                        }
                        rsn.isBlank() -> {
                            validationError = if (isHebrew) "נא להזין סיבה להפחתת הנקודות" else "Please enter a reason"
                        }
                        !awaitingFinalConfirmation -> {
                            awaitingFinalConfirmation = true
                        }
                        else -> {
                            submittedOnce = true
                            onConfirmDeduct(amt, rsn, dialogOperationId)
                        }
                    }
                },
                modifier = Modifier.testTag("confirm_deduct_points_btn")
            )
        },
        dismissButton = {
            TextButton(
                enabled = !isProcessing,
                onClick = onDismiss
            ) {
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
private fun ResetMemberPointsConfirmDialog(
    member: FamilyMember,
    isProcessing: Boolean,
    isHebrew: Boolean,
    onConfirmReset: (reason: String, operationId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    var reasonText by remember {
        mutableStateOf(if (isHebrew) "איפוס יתרת נקודות ע\"י הורה" else "Points reset by parent")
    }
    var submittedOnce by remember { mutableStateOf(false) }
    val dialogOperationId = remember {
        "pts_rst_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    }

    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        containerColor = glass.cardBackground,
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.testTag("reset_points_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = Color(0xFFE8710A),
                    modifier = Modifier.size(26.dp)
                )
                Text(
                    text = if (isHebrew) "איפוס נקודות • ${member.name}" else "Reset Points • ${member.nameEn}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = glass.textPrimary
                    )
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = glass.warningBg,
                    border = BorderStroke(1.dp, Color(0xFFE8710A).copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "האם לאפס את הנקודות של ${member.name}? היתרה הנוכחית היא ${member.earnedPoints} נקודות.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = glass.textPrimary
                        ),
                        modifier = Modifier
                            .padding(14.dp)
                            .testTag("reset_points_warning_text")
                    )
                }

                Text(
                    text = if (isHebrew) {
                        "הפעולה תאפס ל-0 את יתרת הנקודות של ${member.name} בלבד ותשמור תיעוד מלא בהיסטוריית הנקודות. היסטוריית המשימות ושאר בני המשפחה לא ישתנו."
                    } else {
                        "This resets only ${member.nameEn}'s points balance to 0 and logs the previous balance in history. Task history and other members are untouched."
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = glass.textSecondary
                    )
                )

                OutlinedTextField(
                    value = reasonText,
                    onValueChange = { reasonText = it },
                    label = {
                        Text(if (isHebrew) "הערה / סיבת האיפוס" else "Reset note / reason")
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reset_points_reason_input")
                )
            }
        },
        confirmButton = {
            FamilyPrimaryButton(
                text = if (isHebrew) "אפס נקודות ל-0" else "Reset Points to 0",
                enabled = !isProcessing && !submittedOnce,
                onClick = {
                    submittedOnce = true
                    val rsn = reasonText.trim().ifBlank { "איפוס יתרת נקודות ע\"י הורה" }
                    onConfirmReset(rsn, dialogOperationId)
                },
                modifier = Modifier.testTag("confirm_reset_points_btn")
            )
        },
        dismissButton = {
            TextButton(
                enabled = !isProcessing,
                onClick = onDismiss
            ) {
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
private fun TransferPointsBetweenMembersDialog(
    members: List<FamilyMember>,
    isProcessing: Boolean,
    isHebrew: Boolean,
    onConfirmTransfer: (fromMemberId: String, toMemberId: String, amount: Int, reason: String) -> Unit,
    onDismiss: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val childOrAllMembers = remember(members) {
        val kids = members.filter { !it.isParent }
        if (kids.size >= 2) kids else members
    }
    var fromMemberId by remember(childOrAllMembers) {
        mutableStateOf(childOrAllMembers.firstOrNull()?.id.orEmpty())
    }
    var toMemberId by remember(childOrAllMembers) {
        mutableStateOf(childOrAllMembers.getOrNull(1)?.id ?: childOrAllMembers.firstOrNull()?.id.orEmpty())
    }
    var amountText by remember { mutableStateOf("") }
    var reasonText by remember {
        mutableStateOf(if (isHebrew) "עזרה בביצוע משימה של אח/אחות" else "Helping sibling with a task")
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    val fromMember = childOrAllMembers.find { it.id == fromMemberId }
    val toMember = childOrAllMembers.find { it.id == toMemberId }
    val parsedAmount = amountText.trim().toIntOrNull() ?: 0
    val exceedsSenderBalance = fromMember != null && parsedAmount > fromMember.earnedPoints

    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        containerColor = glass.cardBackground,
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.testTag("transfer_points_dialog"),
        title = {
            Text(
                text = if (isHebrew) "🔄 העברת נקודות בין ילדים" else "🔄 Transfer Points Between Members",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = glass.textPrimary
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (isHebrew) "ממי להעביר (נותן הנקודות):" else "Transfer From:",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = glass.textSecondary
                    )
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    childOrAllMembers.forEach { m ->
                        FilterChip(
                            selected = fromMemberId == m.id,
                            onClick = {
                                fromMemberId = m.id
                                if (toMemberId == m.id) {
                                    toMemberId = childOrAllMembers.firstOrNull { it.id != m.id }?.id.orEmpty()
                                }
                                validationError = null
                            },
                            label = {
                                Text("${m.getDisplayName(isHebrew)} (${m.earnedPoints}⭐)")
                            },
                            modifier = Modifier.testTag("transfer_from_member_${m.id}")
                        )
                    }
                }

                Text(
                    text = if (isHebrew) "למי להעביר (מקבל הנקודות):" else "Transfer To:",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = glass.textSecondary
                    )
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    childOrAllMembers.forEach { m ->
                        FilterChip(
                            selected = toMemberId == m.id,
                            onClick = {
                                toMemberId = m.id
                                validationError = null
                            },
                            label = {
                                Text("${m.getDisplayName(isHebrew)} (${m.earnedPoints}⭐)")
                            },
                            modifier = Modifier.testTag("transfer_to_member_${m.id}")
                        )
                    }
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it.filter { ch -> ch.isDigit() }
                        validationError = null
                    },
                    label = { Text(if (isHebrew) "כמות נקודות להעברה" else "Points to transfer") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("transfer_points_amount_input")
                )

                OutlinedTextField(
                    value = reasonText,
                    onValueChange = {
                        reasonText = it
                        validationError = null
                    },
                    label = { Text(if (isHebrew) "סיבת ההעברה (חובה)" else "Transfer reason (required)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("transfer_points_reason_input")
                )

                if (fromMember != null && toMember != null && parsedAmount > 0) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (exceedsSenderBalance) glass.dangerBg else glass.primaryAccentSoftBg,
                        border = BorderStroke(
                            1.dp,
                            if (exceedsSenderBalance) GoogleRed.copy(alpha = 0.5f) else glass.primaryAccent.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (exceedsSenderBalance) {
                                if (isHebrew) {
                                    "אין מספיק נקודות ל-${fromMember.name} (יתרה זמינה: ${fromMember.earnedPoints}⭐)"
                                } else {
                                    "Insufficient balance for ${fromMember.nameEn} (${fromMember.earnedPoints}⭐)"
                                }
                            } else {
                                if (isHebrew) {
                                    "${fromMember.name}: ${fromMember.earnedPoints} ← ${fromMember.earnedPoints - parsedAmount} ⭐ | ${toMember.name}: ${toMember.earnedPoints} ← ${toMember.earnedPoints + parsedAmount} ⭐"
                                } else {
                                    "${fromMember.nameEn}: ${fromMember.earnedPoints} → ${fromMember.earnedPoints - parsedAmount} | ${toMember.nameEn}: ${toMember.earnedPoints} → ${toMember.earnedPoints + parsedAmount}"
                                }
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (exceedsSenderBalance) GoogleRed else glass.primaryAccent
                            ),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                validationError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = GoogleRed,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.testTag("transfer_points_validation_error")
                    )
                }
            }
        },
        confirmButton = {
            FamilyPrimaryButton(
                text = if (isHebrew) "בצע העברה" else "Transfer Points",
                enabled = !isProcessing && !exceedsSenderBalance,
                onClick = {
                    val amt = amountText.trim().toIntOrNull()
                    val rsn = reasonText.trim()
                    when {
                        fromMemberId.isBlank() || toMemberId.isBlank() || fromMemberId == toMemberId -> {
                            validationError = if (isHebrew) "יש לבחור שני בני משפחה שונים להעברה" else "Select two different family members"
                        }
                        amt == null || amt <= 0 -> {
                            validationError = if (isHebrew) "יש להזין מספר נקודות חיובי" else "Enter a positive points amount"
                        }
                        fromMember != null && amt > fromMember.earnedPoints -> {
                            validationError = if (isHebrew) "לא ניתן להעביר יותר מהיתרה הקיימת" else "Cannot exceed available balance"
                        }
                        rsn.isBlank() -> {
                            validationError = if (isHebrew) "חובה להזין סיבה להעברת הנקודות" else "Reason is required"
                        }
                        else -> onConfirmTransfer(fromMemberId, toMemberId, amt, rsn)
                    }
                },
                modifier = Modifier.testTag("confirm_transfer_points_btn")
            )
        },
        dismissButton = {
            TextButton(enabled = !isProcessing, onClick = onDismiss) {
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
private fun GrantHelpingBonusDialog(
    members: List<FamilyMember>,
    isProcessing: Boolean,
    isHebrew: Boolean,
    onConfirmBonus: (memberId: String, amount: Int, reason: String) -> Unit,
    onDismiss: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val childOrAllMembers = remember(members) {
        val kids = members.filter { !it.isParent }
        kids.ifEmpty { members }
    }
    var targetMemberId by remember(childOrAllMembers) {
        mutableStateOf(childOrAllMembers.firstOrNull()?.id.orEmpty())
    }
    var amountText by remember { mutableStateOf("15") }
    var reasonText by remember {
        mutableStateOf(if (isHebrew) "בונוס על עזרה לבן משפחה אחר" else "Bonus for helping another family member")
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        containerColor = glass.cardBackground,
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.testTag("helping_bonus_dialog"),
        title = {
            Text(
                text = if (isHebrew) "🌟 הענקת בונוס על עזרה" else "🌟 Grant Helping Bonus",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = glass.textPrimary
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (isHebrew) {
                        "מוסיף נקודות בונוס לילד שעזר, מבלי להפחית נקודות מאף ילד אחר."
                    } else {
                        "Adds bonus points to the helping child without deducting from anyone else."
                    },
                    style = MaterialTheme.typography.bodySmall.copy(color = glass.textSecondary)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    childOrAllMembers.forEach { m ->
                        FilterChip(
                            selected = targetMemberId == m.id,
                            onClick = { targetMemberId = m.id },
                            label = { Text("${m.getDisplayName(isHebrew)} (${m.earnedPoints}⭐)") },
                            modifier = Modifier.testTag("bonus_member_${m.id}")
                        )
                    }
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it.filter { ch -> ch.isDigit() }
                        validationError = null
                    },
                    label = { Text(if (isHebrew) "נקודות בונוס" else "Bonus points") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("helping_bonus_amount_input")
                )

                OutlinedTextField(
                    value = reasonText,
                    onValueChange = {
                        reasonText = it
                        validationError = null
                    },
                    label = { Text(if (isHebrew) "סיבת הבונוס" else "Bonus reason") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("helping_bonus_reason_input")
                )

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
                text = if (isHebrew) "הענק בונוס 🌟" else "Grant Bonus 🌟",
                enabled = !isProcessing,
                onClick = {
                    val amt = amountText.trim().toIntOrNull()
                    val rsn = reasonText.trim()
                    when {
                        targetMemberId.isBlank() -> {
                            validationError = if (isHebrew) "יש לבחור בן משפחה" else "Select a member"
                        }
                        amt == null || amt <= 0 -> {
                            validationError = if (isHebrew) "נא להזין מספר נקודות חיובי" else "Enter positive points"
                        }
                        rsn.isBlank() -> {
                            validationError = if (isHebrew) "נא להזין סיבה לבונוס" else "Enter a reason"
                        }
                        else -> onConfirmBonus(targetMemberId, amt, rsn)
                    }
                },
                modifier = Modifier.testTag("confirm_helping_bonus_btn")
            )
        },
        dismissButton = {
            TextButton(enabled = !isProcessing, onClick = onDismiss) {
                Text(
                    text = if (isHebrew) "ביטול" else "Cancel",
                    color = glass.textSecondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

