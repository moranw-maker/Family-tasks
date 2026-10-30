package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.AvatarCategory
import com.example.model.FamilyAvatarCatalog
import com.example.model.FamilyAvatarOption
import com.example.model.FamilyMember
import com.example.ui.theme.GoogleBlue
import com.example.ui.theme.LocalSoftGlassColors

@Composable
fun AvatarPickerDialog(
    targetMember: FamilyMember,
    isHebrew: Boolean = true,
    onDismiss: () -> Unit,
    onSaveAvatar: (avatarId: String) -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val initialAvatarId = remember(targetMember.id, targetMember.avatarId, targetMember.avatarEmoji) {
        val fromId = FamilyAvatarCatalog.findById(targetMember.avatarId)?.id
        if (fromId != null) {
            fromId
        } else {
            FamilyAvatarCatalog.allAvatars.firstOrNull { it.emoji == targetMember.avatarEmoji }?.id
                ?: FamilyAvatarCatalog.allAvatars.first().id
        }
    }

    var selectedAvatarId by remember(initialAvatarId) { mutableStateOf(initialAvatarId) }
    var selectedCategory by remember { mutableStateOf<AvatarCategory?>(null) }

    val selectedOption = remember(selectedAvatarId) {
        FamilyAvatarCatalog.findById(selectedAvatarId) ?: FamilyAvatarCatalog.allAvatars.first()
    }
    val displayedAvatars = remember(selectedCategory) {
        FamilyAvatarCatalog.byCategory(selectedCategory)
    }

    val previewMember = remember(targetMember, selectedOption) {
        targetMember.copy(
            avatarId = selectedOption.id,
            avatarEmoji = selectedOption.emoji,
            colorHex = selectedOption.primaryColorHex
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = glass.cardBackground,
            tonalElevation = 10.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(1.dp, glass.cardBorder),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 520.dp)
                .heightIn(max = 680.dp)
                .testTag("avatar_picker_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isHebrew) "🎨 בחירת אווטאר אישי" else "🎨 Choose Personal Avatar",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = glass.textPrimary
                            ),
                            modifier = Modifier.testTag("avatar_picker_title")
                        )
                        Text(
                            text = if (isHebrew) {
                                "עבור ${targetMember.name} • ${FamilyAvatarCatalog.allAvatars.size} דמויות לבחירה"
                            } else {
                                "For ${targetMember.name} • ${FamilyAvatarCatalog.allAvatars.size} avatars"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = glass.textSecondary
                            )
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("close_avatar_picker_icon_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = if (isHebrew) "סגור" else "Close",
                            tint = glass.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Live Preview Banner
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (glass.isDark) Color(0xFF17233B) else Color(0xFFF0F6FF),
                    border = BorderStroke(
                        1.2.dp,
                        Color(selectedOption.primaryColorHex).copy(alpha = 0.55f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("avatar_live_preview_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        FamilyMemberAvatar(
                            member = previewMember,
                            size = 58.dp,
                            isHighlighted = true,
                            modifier = Modifier.testTag("avatar_live_preview_icon")
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = targetMember.name,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = glass.textPrimary
                                )
                            )
                            Text(
                                text = if (isHebrew) {
                                    "נבחר: ${selectedOption.titleHe} (${selectedOption.category.labelHe})"
                                } else {
                                    "Selected: ${selectedOption.titleEn} (${selectedOption.category.labelEn})"
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = glass.primaryAccent
                                ),
                                modifier = Modifier.testTag("avatar_live_preview_label")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Category Filter Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val allSelected = selectedCategory == null
                    FilterChip(
                        selected = allSelected,
                        onClick = { selectedCategory = null },
                        label = {
                            Text(
                                text = if (isHebrew) "🌟 הכל (${FamilyAvatarCatalog.allAvatars.size})" else "🌟 All (${FamilyAvatarCatalog.allAvatars.size})",
                                fontWeight = if (allSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        modifier = Modifier.testTag("avatar_category_ALL")
                    )

                    AvatarCategory.entries.forEach { category ->
                        val isCatSelected = selectedCategory == category
                        FilterChip(
                            selected = isCatSelected,
                            onClick = {
                                selectedCategory = if (isCatSelected) null else category
                            },
                            label = {
                                Text(
                                    text = "${category.emoji} ${if (isHebrew) category.labelHe else category.labelEn}",
                                    fontWeight = if (isCatSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            modifier = Modifier.testTag("avatar_category_${category.name}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Responsive Grid of Avatars (3 columns per row)
                val rows = remember(displayedAvatars) { displayedAvatars.chunked(3) }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(min = 220.dp, max = 350.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("avatar_picker_grid"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rows.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowItems.forEach { option ->
                                AvatarOptionGridCard(
                                    option = option,
                                    isSelected = option.id == selectedAvatarId,
                                    isHebrew = isHebrew,
                                    onClick = { selectedAvatarId = option.id },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(3 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("cancel_avatar_picker_btn")
                    ) {
                        Text(
                            text = if (isHebrew) "ביטול" else "Cancel",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            onSaveAvatar(selectedAvatarId)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = glass.primaryAccent,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp)
                            .testTag("save_avatar_confirm_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isHebrew) "שמור אווטאר" else "Save Avatar",
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarOptionGridCard(
    option: FamilyAvatarOption,
    isSelected: Boolean,
    isHebrew: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = LocalSoftGlassColors.current
    val primaryColor = Color(option.primaryColorHex)
    val secondaryColor = Color(option.secondaryColorHex)

    val containerBg by animateColorAsState(
        targetValue = when {
            isSelected && glass.isDark -> Color(0xFF1E3A6D)
            isSelected && !glass.isDark -> Color(0xFFDCEBFE)
            glass.isDark -> Color(0xFF151F34)
            else -> Color.White
        },
        label = "avatarCardBg"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isSelected) GoogleBlue else glass.cardBorder,
        label = "avatarCardBorder"
    )

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = containerBg,
        border = BorderStroke(if (isSelected) 2.2.dp else 1.dp, borderColor),
        shadowElevation = if (isSelected) 6.dp else 1.dp,
        modifier = modifier
            .height(98.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("avatar_option_${option.id}")
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 8.dp)
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(GoogleBlue)
                        .testTag("avatar_selected_badge_${option.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = if (isHebrew) "נבחר" else "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .shadow(2.dp, CircleShape)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color.White,
                                    primaryColor.copy(alpha = 0.35f),
                                    secondaryColor.copy(alpha = 0.25f)
                                )
                            )
                        )
                        .border(1.2.dp, primaryColor.copy(alpha = 0.65f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option.emoji,
                        fontSize = 23.sp
                    )
                    if (option.badgeEmoji.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(if (glass.isDark) Color(0xFF152036) else Color.White)
                                .border(0.7.dp, primaryColor.copy(alpha = 0.6f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = option.badgeEmoji,
                                fontSize = 9.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (isHebrew) option.titleHe else option.titleEn,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (isSelected) {
                            if (glass.isDark) Color(0xFF93C5FD) else Color(0xFF1D4ED8)
                        } else {
                            glass.textPrimary
                        },
                        fontSize = 11.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
