package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.theme.*

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
    val kids = members.filter { it.role == UserRole.CHILD }
    var selectedDay by remember { mutableStateOf(SchoolDay.SUNDAY) }

    val activeKid = members.find { it.id == selectedChildId } ?: kids.firstOrNull() ?: members.first()
    val kidPeriods = periods.filter { it.childId == activeKid.id }
    val dayPeriods = kidPeriods
        .filter { it.day == selectedDay }
        .sortedBy { it.periodIndex }

    Scaffold(
        floatingActionButton = {
            if (currentMember.isParent) {
                Surface(
                    onClick = onAddPeriod,
                    shape = RoundedCornerShape(24.dp),
                    color = VibrantActionBlue,
                    contentColor = Color.White,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .height(46.dp)
                        .testTag("add_period_fab")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = if (isHebrew) "הוסף שיעור" else "Add Period",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            androidx.compose.ui.graphics.Color(0xFFEBF3FE),
                            androidx.compose.ui.graphics.Color(0xFFF7F9FD),
                            androidx.compose.ui.graphics.Color.White
                        )
                    )
                )
        ) {
            // Quick Screen Help Guide
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                com.example.ui.components.ScreenHelpBanner(
                    title = if (isHebrew) "מערכת שעות ולימודים" else "School Schedule & Periods",
                    tips = if (isHebrew) listOf(
                        "בחירת תלמיד/ה: בחרו בלשוניות למעלה בין הילדים לצפייה במערכת האישית.",
                        "מעבר תצוגות: צפייה יומית מפורטת או טבלה שבועית מלאה.",
                        "הוספת שיעור: מנהלים יכולים ללחוץ על הכפתור הקומפקטי בתחתית להוספת שיעור.",
                        "התראות שיעור: כל שיעור שולח התראת הכנה 10 דקות לפני תחילתו."
                    ) else listOf(
                        "Select Child: Tap child chips on top to view their schedule.",
                        "Views: Toggle between Daily view and Weekly table grid.",
                        "Add Period: Admins can use the compact bottom button to add periods.",
                        "Alerts: Receive notifications 10 minutes prior to school periods."
                    ),
                    isHebrew = isHebrew
                )
            }

            // Child Selector Pills & View Mode Switcher
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // View Mode Toggle (Daily List vs Weekly Table Grid)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
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

                if (currentMember.isAdmin) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = GoogleBlueLight
                    ) {
                        Text(
                            text = if (isHebrew) "מצב עריכה ✏️" else "Admin Edit ✏️",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = GoogleBlueDark,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Kids Tabs Row
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(kids) { kid ->
                    val isSelected = kid.id == activeKid.id
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) GoogleBlue else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { onSelectChild(kid.id) }
                            .testTag("child_tab_${kid.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(text = kid.avatarEmoji, fontSize = 16.sp)
                            Text(
                                text = kid.name + (if (kid.schoolGrade != null) " (${kid.schoolGrade})" else ""),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (timetableViewMode == TimetableViewMode.WEEKLY_TABLE) {
                // Table / Calendar Grid View
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
                // Daily List View
                // Days of the Week Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SchoolDay.values().take(5).forEach { day ->
                        val isDaySelected = day == selectedDay
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isDaySelected) GoogleBlueLight else MaterialTheme.colorScheme.surface,
                            border = if (isDaySelected) androidx.compose.foundation.BorderStroke(1.5.dp, GoogleBlue) else null,
                            modifier = Modifier
                                .weight(1f)
                                .padding(2.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { selectedDay = day }
                                .testTag("day_tab_${day.name}")
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = day.getDisplayName(isHebrew),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isDaySelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isDaySelected) GoogleBlueDark else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Periods Timeline List
                if (dayPeriods.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "🎒", fontSize = 48.sp)
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (isHebrew)
                                    "אין שיעורים במערכת ליום ${selectedDay.getDisplayName(isHebrew)}"
                                else
                                    "No periods on ${selectedDay.getDisplayName(isHebrew)}",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
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

@Composable
fun TimetableViewPill(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) GoogleBlue else Color.Transparent,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
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
    val schoolDays = listOf(
        SchoolDay.SUNDAY,
        SchoolDay.MONDAY,
        SchoolDay.TUESDAY,
        SchoolDay.WEDNESDAY,
        SchoolDay.THURSDAY
    )
    val periodIndices = (1..6).toList()
    val horizontalScrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Text(
            text = if (isHebrew) "לוח שעות שבועי - $childName" else "Weekly Timetable - $childName",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = GoogleBlue)
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Horizontally scrollable table matrix
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineLight),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .horizontalScroll(horizontalScrollState)
            ) {
                // Header Row (Days of the week)
                Row(modifier = Modifier.padding(vertical = 6.dp)) {
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "שעה", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                    }

                    schoolDays.forEach { day ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = GoogleBlueLight,
                            modifier = Modifier
                                .width(110.dp)
                                .padding(horizontal = 2.dp)
                        ) {
                            Text(
                                text = day.getDisplayName(isHebrew),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = GoogleBlueDark
                                ),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = OutlineLight.copy(alpha = 0.5f))

                // Rows for Period 1 to 6
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(periodIndices) { periodIndex ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            // Period Index column
                            Box(
                                modifier = Modifier
                                    .width(44.dp)
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$periodIndex",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = GoogleBlue
                                    )
                                )
                            }

                            // Day cells
                            schoolDays.forEach { day ->
                                val period = kidPeriods.find { it.day == day && it.periodIndex == periodIndex }

                                Box(
                                    modifier = Modifier
                                        .width(110.dp)
                                        .height(72.dp)
                                        .padding(2.dp)
                                ) {
                                    if (period != null) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color(period.colorHex).copy(alpha = 0.15f),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(period.colorHex).copy(alpha = 0.4f)),
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable {
                                                    if (isAdmin) onEditPeriod(period)
                                                }
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(4.dp),
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
                                                            color = Color(period.colorHex)
                                                        ),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (isAdmin) {
                                                        Icon(
                                                            imageVector = Icons.Default.Edit,
                                                            contentDescription = "Edit",
                                                            tint = Color(period.colorHex),
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                    }
                                                }

                                                Text(
                                                    text = "${period.startTime}-${period.endTime}",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 8.5.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                )

                                                if (period.roomNumber.isNotBlank()) {
                                                    Text(
                                                        text = period.roomNumber,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 8.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        ),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
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
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Period number circle badge
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(period.colorHex).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${period.periodIndex}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(period.colorHex)
                    )
                )
            }

            // Subject & Details
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = period.subjectName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "${period.startTime} - ${period.endTime}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (period.roomNumber.isNotBlank()) {
                        Text(
                            text = "📍 " + period.roomNumber,
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                    if (period.teacherName.isNotBlank()) {
                        Text(
                            text = "👤 " + period.teacherName,
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                }
            }

            // Actions: Edit, Test Notification, Delete
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Test push alert button
                IconButton(
                    onClick = onTestAlert,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("test_alert_period_${period.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = "Test Notification",
                        tint = GoogleBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (isAdmin) {
                    // Edit period button
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("edit_period_${period.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = GoogleBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
