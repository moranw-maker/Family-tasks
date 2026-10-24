package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.example.model.*
import com.example.ui.components.FamilyEmptyState
import com.example.ui.components.FamilyGlassCard
import com.example.ui.components.FamilyMemberAvatar
import com.example.ui.components.FamilyPrimaryButton
import com.example.ui.components.FamilySectionHeader
import com.example.ui.components.FamilyStatusChip
import com.example.ui.components.SoftGlassScreenBackground
import com.example.ui.components.velvetNeumorphicSheen
import com.example.ui.theme.*
import java.time.LocalDate
import kotlin.math.abs

private fun resolveSubjectAccentColor(subjectName: String, fallbackHex: Long, isDark: Boolean): Color {
    val paletteLight = listOf(
        Color(0xFF2563EB),
        Color(0xFF0D9488),
        Color(0xFF7C3AED),
        Color(0xFFD97706),
        Color(0xFF16A34A),
        Color(0xFFDB2777),
        Color(0xFF4F46E5)
    )
    val paletteDark = listOf(
        Color(0xFF60A5FA),
        Color(0xFF2DD4BF),
        Color(0xFFA78BFA),
        Color(0xFFFBBF24),
        Color(0xFF4ADE80),
        Color(0xFFF472B6),
        Color(0xFF818CF8)
    )
    val palette = if (isDark) paletteDark else paletteLight
    if (subjectName.isBlank()) return Color(fallbackHex)
    val index = abs(subjectName.trim().hashCode()) % palette.size
    return palette[index]
}

@Composable
fun TimetableScreen(
    periods: List<SchedulePeriod>,
    members: List<FamilyMember>,
    currentMember: FamilyMember,
    selectedChildId: String,
    timetableViewMode: TimetableViewMode,
    isHebrew: Boolean,
    onSelectChild: (String) -> Unit,
    onViewModeChange: (TimetableViewMode) -> Unit,
    onAddPeriod: () -> Unit,
    onEditPeriod: (SchedulePeriod) -> Unit,
    onDeletePeriod: (String) -> Unit,
    onTestAlert: (SchedulePeriod, String) -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val kids = members.filter { it.role == UserRole.CHILD }

    // Auto-select current school day (Sun-Fri) if today is a school day; otherwise default to Sunday
    val defaultSchoolDay = remember {
        SchoolDay.fromJavaDayOfWeek(LocalDate.now().dayOfWeek) ?: SchoolDay.SUNDAY
    }
    var selectedDay by remember { mutableStateOf(defaultSchoolDay) }

    val activeKid = members.find { it.id == selectedChildId } ?: kids.firstOrNull() ?: members.first()
    val kidPeriods = periods.filter { it.childId == activeKid.id }
    val dayPeriods = kidPeriods
        .filter { it.day == selectedDay }
        .sortedBy { it.periodIndex }

    Scaffold(
        containerColor = Color.Transparent
    ) { innerPadding ->
        SoftGlassScreenBackground(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("timetable_screen")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Quick Screen Help Guide
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    com.example.ui.components.ScreenHelpBanner(
                        title = if (isHebrew) "מערכת שעות בית ספרית (א׳-ו׳)" else "School Timetable (Sun-Fri)",
                        tips = if (isHebrew) listOf(
                            "בחירת תלמיד/ה: בחרו בלשוניות למעלה בין הילדים לצפייה במערכת האישית (א׳-ו׳).",
                            "מעבר תצוגות: צפייה יומית מפורטת או טבלה שבועית מלאה.",
                            "התראות שיעור: כל שיעור שולח התראת הכנה לפני תחילתו."
                        ) else listOf(
                            "Select Child: Tap child chips on top to view their schedule (Sun-Fri).",
                            "Views: Toggle between Daily view and Weekly grid.",
                            "Alerts: Receive notifications prior to school periods."
                        ),
                        isHebrew = isHebrew
                    )
                }

                // Luxury Soft Neumorphism Top Control Card: View Switcher + Single Add Period Button + Child Selector Tabs
                FamilyGlassCard(
                    cornerRadius = 24.dp,
                    isTintedBlue = true,
                    elevation = 7.dp,
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val switcherShape = RoundedCornerShape(15.dp)
                        Surface(
                            shape = switcherShape,
                            color = Color.Transparent,
                            border = BorderStroke(0.9.dp, glass.cardBorder),
                            modifier = Modifier
                                .clip(switcherShape)
                                .background(
                                    Brush.verticalGradient(
                                        colors = if (glass.isDark) {
                                            listOf(Color(0xFF0E1628), Color(0xFF162138))
                                        } else {
                                            listOf(Color(0xFFE8EFFB), Color(0xFFF6F9FE))
                                        }
                                    ),
                                    switcherShape
                                )
                                .velvetNeumorphicSheen(isDark = glass.isDark, cornerRadius = 15.dp, isRecessed = true)
                        ) {
                            Row(modifier = Modifier.padding(3.dp)) {
                                TimetableViewPill(
                                    title = if (isHebrew) "יומי" else "Daily",
                                    isSelected = timetableViewMode == TimetableViewMode.DAILY_LIST,
                                    onClick = { onViewModeChange(TimetableViewMode.DAILY_LIST) }
                                )
                                TimetableViewPill(
                                    title = if (isHebrew) "טבלה שבועית" else "Weekly Grid",
                                    isSelected = timetableViewMode == TimetableViewMode.WEEKLY_TABLE,
                                    onClick = { onViewModeChange(TimetableViewMode.WEEKLY_TABLE) }
                                )
                            }
                        }

                        if (currentMember.isParent) {
                            FamilyPrimaryButton(
                                text = if (isHebrew) "הוסף שיעור" else "Add Period",
                                leadingIcon = Icons.Default.Add,
                                onClick = onAddPeriod,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                                modifier = Modifier.testTag("add_period_fab")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Kids Tabs Row with 3D Neumorphic Pill & FamilyMemberAvatar
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(kids) { kid ->
                            val isSelected = kid.id == activeKid.id
                            val tabShape = RoundedCornerShape(16.dp)
                            Surface(
                                shape = tabShape,
                                color = Color.Transparent,
                                border = BorderStroke(
                                    if (isSelected) 1.3.dp else 0.9.dp,
                                    if (isSelected) Color.White.copy(alpha = 0.45f) else glass.cardBorder
                                ),
                                modifier = Modifier
                                    .shadow(
                                        elevation = if (isSelected) 6.dp else 2.dp,
                                        shape = tabShape,
                                        spotColor = if (isSelected) glass.primaryAccent.copy(alpha = 0.40f) else Color.Black.copy(alpha = 0.15f)
                                    )
                                    .clip(tabShape)
                                    .background(
                                        Brush.verticalGradient(
                                            colors = if (isSelected) {
                                                if (glass.isDark) {
                                                    listOf(Color(0xFF60A5FA), Color(0xFF3B82F6), Color(0xFF4F46E5))
                                                } else {
                                                    listOf(Color(0xFF3B82F6), Color(0xFF2563EB), Color(0xFF1D4ED8))
                                                }
                                            } else if (glass.isDark) {
                                                listOf(Color(0xFF202D47), Color(0xFF151F33))
                                            } else {
                                                listOf(Color.White, Color(0xFFF0F5FE))
                                            }
                                        ),
                                        tabShape
                                    )
                                    .clickable { onSelectChild(kid.id) }
                                    .testTag("child_tab_${kid.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    FamilyMemberAvatar(
                                        member = kid,
                                        size = 24.dp,
                                        isHighlighted = isSelected
                                    )
                                    Text(
                                        text = kid.name + (if (kid.schoolGrade != null) " (${kid.schoolGrade})" else ""),
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isSelected) {
                                                Color.White
                                            } else {
                                                glass.textPrimary
                                            }
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (timetableViewMode == TimetableViewMode.WEEKLY_TABLE) {
                    WeeklyTimetableTableView(
                        kidPeriods = kidPeriods,
                        childName = activeKid.name,
                        isAdmin = currentMember.isAdmin,
                        isHebrew = isHebrew,
                        onEditPeriod = onEditPeriod,
                        onDeletePeriod = onDeletePeriod,
                        onTestAlert = onTestAlert
                    )
                } else {
                    // School Days of the Week Selector (Strictly Sunday through Friday: א׳ | ב׳ | ג׳ | ד׳ | ה׳ | ו׳)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SchoolDay.schoolDays.forEach { day ->
                            val isDaySelected = day == selectedDay
                            val dayPeriodsCount = kidPeriods.count { it.day == day }
                            val dayShape = RoundedCornerShape(16.dp)
                            Surface(
                                shape = dayShape,
                                color = Color.Transparent,
                                border = BorderStroke(
                                    if (isDaySelected) 1.4.dp else 0.9.dp,
                                    if (isDaySelected) glass.primaryAccent else glass.cardBorder
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 2.dp)
                                    .shadow(
                                        elevation = if (isDaySelected) 5.dp else 2.dp,
                                        shape = dayShape,
                                        spotColor = if (isDaySelected) glass.primaryAccent.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.14f)
                                    )
                                    .clip(dayShape)
                                    .background(
                                        Brush.verticalGradient(
                                            colors = if (isDaySelected) {
                                                if (glass.isDark) {
                                                    listOf(Color(0xFF263B6A), Color(0xFF1B2B4E))
                                                } else {
                                                    listOf(Color.White, Color(0xFFDFEBFE))
                                                }
                                            } else if (glass.isDark) {
                                                listOf(Color(0xFF1F2B44), Color(0xFF151F32))
                                            } else {
                                                listOf(Color.White, Color(0xFFF3F6FC))
                                            }
                                        ),
                                        dayShape
                                    )
                                    .velvetNeumorphicSheen(isDark = glass.isDark, cornerRadius = 16.dp)
                                    .clickable { selectedDay = day }
                                    .testTag("day_tab_${day.name}")
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = if (isHebrew) day.shortHe else day.getDisplayName(false).take(3),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = if (isDaySelected) FontWeight.ExtraBold else FontWeight.Medium,
                                            color = if (isDaySelected) glass.primaryAccent else glass.textSecondary
                                        ),
                                        maxLines = 1
                                    )
                                    if (dayPeriodsCount > 0) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = if (isDaySelected) glass.primaryAccent else glass.primaryAccentSoftBg
                                        ) {
                                            Text(
                                                text = "$dayPeriodsCount",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = if (isDaySelected) {
                                                    Color.White
                                                } else {
                                                    glass.primaryAccent
                                                },
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (dayPeriods.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            FamilyEmptyState(
                                emoji = "🎒",
                                title = if (isHebrew)
                                    "אין שיעורים במערכת ליום ${selectedDay.getDisplayName(isHebrew)}"
                                else
                                    "No periods on ${selectedDay.getDisplayName(isHebrew)}",
                                subtitle = if (currentMember.isParent) {
                                    if (isHebrew) "לחצו על 'הוסף שיעור' למעלה להוספת שיעור למערכת" else "Tap 'Add Period' above to add a class"
                                } else {
                                    if (isHebrew) "יום חופשי משיעורים במערכת" else "No classes scheduled for this day"
                                }
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 110.dp, top = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(dayPeriods, key = { it.id }) { period ->
                                PeriodCardItem(
                                    period = period,
                                    childName = activeKid.name,
                                    isAdmin = currentMember.isAdmin,
                                    isHebrew = isHebrew,
                                    onEdit = { onEditPeriod(period) },
                                    onDelete = { onDeletePeriod(period.id) },
                                    onTestAlert = { onTestAlert(period, activeKid.name) }
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
fun TimetableViewPill(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val pillShape = RoundedCornerShape(12.dp)
    Surface(
        shape = pillShape,
        color = Color.Transparent,
        modifier = Modifier
            .then(
                if (isSelected) {
                    Modifier
                        .shadow(
                            elevation = 4.dp,
                            shape = pillShape,
                            spotColor = glass.primaryAccent.copy(alpha = 0.35f)
                        )
                        .clip(pillShape)
                        .background(
                            Brush.verticalGradient(
                                colors = if (glass.isDark) {
                                    listOf(Color(0xFF60A5FA), Color(0xFF3B82F6))
                                } else {
                                    listOf(Color(0xFF3B82F6), Color(0xFF2563EB))
                                }
                            ),
                            pillShape
                        )
                } else {
                    Modifier.clip(pillShape)
                }
            )
            .clickable { onClick() }
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                color = if (isSelected) {
                    Color.White
                } else {
                    glass.textSecondary
                }
            ),
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun WeeklyTimetableTableView(
    kidPeriods: List<SchedulePeriod>,
    childName: String,
    isAdmin: Boolean,
    isHebrew: Boolean,
    onEditPeriod: (SchedulePeriod) -> Unit,
    onDeletePeriod: (String) -> Unit,
    onTestAlert: (SchedulePeriod, String) -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val schoolDays = SchoolDay.schoolDays
    val maxPeriod = maxOf(6, kidPeriods.maxOfOrNull { it.periodIndex } ?: 6)
    val periodIndices = (1..maxPeriod).toList()
    val horizontalScrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        FamilySectionHeader(
            title = if (isHebrew) "לוח שעות שבועי (א׳-ו׳) - $childName" else "Weekly Timetable (Sun-Fri) - $childName"
        )
        Spacer(modifier = Modifier.height(8.dp))

        FamilyGlassCard(
            cornerRadius = 24.dp,
            elevation = 7.dp,
            contentPadding = PaddingValues(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScrollState)
            ) {
                // Header Row (Days of the week: Sun-Fri)
                Row(modifier = Modifier.padding(vertical = 6.dp)) {
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isHebrew) "שעה" else "#",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = glass.textSecondary
                            )
                        )
                    }

                    schoolDays.forEach { day ->
                        val headerCellShape = RoundedCornerShape(10.dp)
                        Surface(
                            shape = headerCellShape,
                            color = Color.Transparent,
                            border = BorderStroke(0.9.dp, glass.primaryAccent.copy(alpha = 0.28f)),
                            modifier = Modifier
                                .width(112.dp)
                                .padding(horizontal = 2.dp)
                                .shadow(2.dp, headerCellShape, spotColor = glass.primaryAccent.copy(alpha = 0.20f))
                                .background(
                                    Brush.verticalGradient(
                                        colors = if (glass.isDark) {
                                            listOf(Color(0xFF243763), glass.primaryAccentSoftBg)
                                        } else {
                                            listOf(Color.White, glass.primaryAccentSoftBg)
                                        }
                                    ),
                                    headerCellShape
                                )
                        ) {
                            Text(
                                text = day.getDisplayName(isHebrew),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = glass.primaryAccent
                                ),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = glass.divider)

                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(periodIndices) { periodIndex ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(44.dp)
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$periodIndex",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = glass.primaryAccent
                                    )
                                )
                            }

                            schoolDays.forEach { day ->
                                val period = kidPeriods.find { it.day == day && it.periodIndex == periodIndex }

                                Box(
                                    modifier = Modifier
                                        .width(112.dp)
                                        .height(74.dp)
                                        .padding(2.dp)
                                ) {
                                    if (period != null) {
                                        val subjectAccent = resolveSubjectAccentColor(
                                            period.subjectName,
                                            period.colorHex,
                                            glass.isDark
                                        )
                                        val cellShape = RoundedCornerShape(12.dp)
                                        Surface(
                                            shape = cellShape,
                                            color = Color.Transparent,
                                            border = BorderStroke(0.95.dp, subjectAccent.copy(alpha = 0.45f)),
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .shadow(3.dp, cellShape, spotColor = subjectAccent.copy(alpha = 0.25f))
                                                .clip(cellShape)
                                                .background(
                                                    Brush.verticalGradient(
                                                        colors = if (glass.isDark) {
                                                            listOf(
                                                                subjectAccent.copy(alpha = 0.26f),
                                                                subjectAccent.copy(alpha = 0.14f)
                                                            )
                                                        } else {
                                                            listOf(
                                                                Color.White,
                                                                subjectAccent.copy(alpha = 0.15f)
                                                            )
                                                        }
                                                    ),
                                                    cellShape
                                                )
                                                .clickable {
                                                    if (isAdmin) onEditPeriod(period)
                                                }
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(6.dp),
                                                verticalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = period.subjectName,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            color = subjectAccent
                                                        ),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (isAdmin) {
                                                        Icon(
                                                            imageVector = Icons.Default.Edit,
                                                            contentDescription = "Edit",
                                                            tint = subjectAccent,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                    }
                                                }

                                                Text(
                                                    text = "${period.startTime}-${period.endTime}",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 8.5.sp,
                                                        color = glass.textSecondary
                                                    )
                                                )

                                                if (period.roomNumber.isNotBlank()) {
                                                    Text(
                                                        text = period.roomNumber,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 8.sp,
                                                            color = glass.textMuted
                                                        ),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = glass.screenBackground.copy(alpha = 0.55f),
                                            border = BorderStroke(0.6.dp, glass.cardBorder.copy(alpha = 0.5f)),
                                            modifier = Modifier.fillMaxSize()
                                        ) {}
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
fun PeriodCardItem(
    period: SchedulePeriod,
    childName: String,
    isAdmin: Boolean,
    isHebrew: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTestAlert: () -> Unit
) {
    val glass = LocalSoftGlassColors.current
    val subjectAccent = remember(period.subjectName, period.colorHex, glass.isDark) {
        resolveSubjectAccentColor(period.subjectName, period.colorHex, glass.isDark)
    }

    FamilyGlassCard(
        cornerRadius = 22.dp,
        accentColor = subjectAccent,
        elevation = 6.dp,
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("period_card_${period.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 3D Raised Period Number Squircle Badge with Subject Accent
            val badgeShape = RoundedCornerShape(15.dp)
            Surface(
                shape = badgeShape,
                color = Color.Transparent,
                border = BorderStroke(1.1.dp, subjectAccent.copy(alpha = 0.45f)),
                modifier = Modifier
                    .size(46.dp)
                    .shadow(
                        elevation = 4.dp,
                        shape = badgeShape,
                        spotColor = subjectAccent.copy(alpha = 0.35f)
                    )
                    .background(
                        Brush.verticalGradient(
                            colors = if (glass.isDark) {
                                listOf(
                                    subjectAccent.copy(alpha = 0.32f),
                                    subjectAccent.copy(alpha = 0.16f)
                                )
                            } else {
                                listOf(
                                    Color.White,
                                    subjectAccent.copy(alpha = 0.18f)
                                )
                            }
                        ),
                        badgeShape
                    )
                    .velvetNeumorphicSheen(isDark = glass.isDark, cornerRadius = 15.dp, accentColor = subjectAccent)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "${period.periodIndex}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = subjectAccent
                        )
                    )
                }
            }

            // Subject & Velvet Status Chips
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = period.subjectName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = glass.textPrimary
                        )
                    )

                    FamilyStatusChip(
                        emoji = "🕒",
                        label = "${period.startTime} - ${period.endTime}",
                        containerColor = glass.primaryAccentSoftBg,
                        contentColor = glass.primaryAccent
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (period.roomNumber.isNotBlank()) {
                        FamilyStatusChip(
                            emoji = "📍",
                            label = period.roomNumber,
                            containerColor = glass.cardBackground,
                            contentColor = glass.textSecondary
                        )
                    }
                    if (period.teacherName.isNotBlank()) {
                        FamilyStatusChip(
                            emoji = "👤",
                            label = period.teacherName,
                            containerColor = glass.cardBackground,
                            contentColor = glass.textSecondary
                        )
                    }
                }
            }

            // Raised 3D Action Pills: Test Notification, Edit, Delete
            val btnShape = RoundedCornerShape(11.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Surface(
                    shape = btnShape,
                    color = Color.Transparent,
                    border = BorderStroke(0.85.dp, glass.primaryAccent.copy(alpha = 0.30f)),
                    modifier = Modifier
                        .size(34.dp)
                        .shadow(3.dp, btnShape, spotColor = glass.primaryAccent.copy(alpha = 0.25f))
                        .clip(btnShape)
                        .background(
                            Brush.verticalGradient(
                                colors = if (glass.isDark) {
                                    listOf(Color(0xFF243763), glass.primaryAccentSoftBg)
                                } else {
                                    listOf(Color.White, glass.primaryAccentSoftBg)
                                }
                            ),
                            btnShape
                        )
                        .clickable { onTestAlert() }
                        .testTag("test_alert_period_${period.id}")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = "Test Notification",
                            tint = glass.primaryAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (isAdmin) {
                    Surface(
                        shape = btnShape,
                        color = Color.Transparent,
                        border = BorderStroke(0.85.dp, glass.cardBorder),
                        modifier = Modifier
                            .size(34.dp)
                            .shadow(3.dp, btnShape, spotColor = glass.primaryAccent.copy(alpha = 0.20f))
                            .clip(btnShape)
                            .background(
                                Brush.verticalGradient(
                                    colors = if (glass.isDark) {
                                        listOf(Color(0xFF233250), Color(0xFF172238))
                                    } else {
                                        listOf(Color.White, Color(0xFFF0F5FE))
                                    }
                                ),
                                btnShape
                            )
                            .clickable { onEdit() }
                            .testTag("edit_period_${period.id}")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit",
                                tint = glass.primaryAccent,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    Surface(
                        shape = btnShape,
                        color = Color.Transparent,
                        border = BorderStroke(0.85.dp, glass.dangerText.copy(alpha = 0.32f)),
                        modifier = Modifier
                            .size(34.dp)
                            .shadow(3.dp, btnShape, spotColor = glass.dangerText.copy(alpha = 0.25f))
                            .clip(btnShape)
                            .background(
                                Brush.verticalGradient(
                                    colors = if (glass.isDark) {
                                        listOf(glass.dangerBg, Color(0xFF2C0808))
                                    } else {
                                        listOf(Color.White, glass.dangerBg)
                                    }
                                ),
                                btnShape
                            )
                            .clickable { onDelete() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete",
                                tint = glass.dangerText,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

