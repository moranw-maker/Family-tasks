package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * Pure utility for strict 24-hour (`HH:mm`) time parsing, formatting, and local timezone preservation.
 * Never formats with AM/PM.
 */
object TimeFormatUtils {

    /**
     * Parses a time string into `(hour24, minute)` coerced to valid 24-hour bounds (`0..23`, `0..59`).
     */
    fun parse24Hour(rawTime: String?, defaultHour: Int = 16, defaultMinute: Int = 0): Pair<Int, Int> {
        if (rawTime.isNullOrBlank()) {
            return Pair(defaultHour.coerceIn(0, 23), defaultMinute.coerceIn(0, 59))
        }
        val cleaned = rawTime.trim()
            .replace("AM", "", ignoreCase = true)
            .replace("PM", "", ignoreCase = true)
            .trim()
        val parts = cleaned.split(":")
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: defaultHour
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: defaultMinute
        return Pair(h.coerceIn(0, 23), m.coerceIn(0, 59))
    }

    /**
     * Formats `(hour24, minute)` strictly in 24-hour `HH:mm` format using `Locale.ROOT` (never AM/PM).
     */
    fun format24Hour(hour24: Int, minute: Int): String {
        val h = hour24.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        return String.format(Locale.ROOT, "%02d:%02d", h, m)
    }

    /**
     * Normalizes any input time string into strict 24-hour `HH:mm`.
     */
    fun normalize24HourString(rawTime: String?, fallback: String = "16:00"): String {
        val (defH, defM) = parse24Hour(fallback, 16, 0)
        val (h, m) = parse24Hour(rawTime, defH, defM)
        return format24Hour(h, m)
    }

    /**
     * Converts a `dueTimeFormatted` string (`HH:mm`) into total minutes from midnight (`0..1439`)
     * for deterministic chronological sorting in the Calendar agenda.
     */
    fun toMinutesSinceMidnight(rawTime: String?): Int {
        val (h, m) = parse24Hour(rawTime, 16, 0)
        return h * 60 + m
    }

    /**
     * Combines an existing [dateEpochDay] with a new 24-hour `HH:mm` time in [zoneId],
     * guaranteeing that changing the time never alters the selected calendar date.
     */
    fun combineDateAnd24HourTimeToEpochMillis(
        dateEpochDay: Long,
        time24h: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Pair<Long, Long> {
        val date = LocalDate.ofEpochDay(dateEpochDay)
        val (h, m) = parse24Hour(time24h)
        val dateTime = LocalDateTime.of(date, LocalTime.of(h, m))
        val millis = dateTime.atZone(zoneId).toInstant().toEpochMilli()
        return Pair(date.toEpochDay(), millis)
    }

    /**
     * Extracts `(dateEpochDay, time24h)` from an epoch timestamp in the given local [zoneId].
     */
    fun extractDateAnd24HourTime(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Pair<Long, String> {
        val zdt = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
        return Pair(
            zdt.toLocalDate().toEpochDay(),
            format24Hour(zdt.hour, zdt.minute)
        )
    }
}

/**
 * Reusable soft-tactile Material 3 24-hour time field used across Tasks, Calendar, and Timetable.
 * Opens [FamilyTimeSelectionDialog] with both clock dial and direct numeric entry in strict 24h `HH:mm` format.
 */
@Composable
fun TimePickerField(
    label: String,
    time: String,
    onTimeSelected: (String) -> Unit,
    isHebrew: Boolean,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }
    val displayTime = remember(time) { TimeFormatUtils.normalize24HourString(time, "16:00") }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 1.dp,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { showDialog = true }
            .defaultMinSize(minHeight = 48.dp)
            .testTag("time_picker_field_$label")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = displayTime,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp
                    ),
                    modifier = Modifier.testTag("time_picker_value_$label")
                )
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = if (isHebrew) "בחירת שעה" else "Select Time",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    if (showDialog) {
        FamilyTimeSelectionDialog(
            initialTime = displayTime,
            title = label,
            isHebrew = isHebrew,
            onDismiss = { showDialog = false },
            onConfirm = { chosenTime ->
                onTimeSelected(TimeFormatUtils.normalize24HourString(chosenTime, displayTime))
                showDialog = false
            }
        )
    }
}

/**
 * Reusable Material 3 24-hour time picker dialog supporting:
 * 1. Strict 24-hour `HH:mm` format (`is24Hour = true`, never displays AM/PM).
 * 2. Both clock-based selection ([TimePicker]) and direct numeric keyboard entry ([TimeInput]).
 * 3. Quick 24-hour presets for one-tap selection.
 * 4. Clear Hebrew Confirm ("אישור") and Cancel ("ביטול") buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyTimeSelectionDialog(
    initialTime: String,
    title: String,
    isHebrew: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val parsedParts = remember(initialTime) {
        TimeFormatUtils.parse24Hour(initialTime, defaultHour = 16, defaultMinute = 0)
    }

    // Key the TimePickerState when a preset is tapped so it updates cleanly
    var presetSeed by remember { mutableIntStateOf(0) }
    var currentHour by remember { mutableIntStateOf(parsedParts.first) }
    var currentMinute by remember { mutableIntStateOf(parsedParts.second) }
    var isNumericInputMode by remember { mutableStateOf(false) }

    val timePickerState = remember(presetSeed) {
        TimePickerState(
            initialHour = currentHour.coerceIn(0, 23),
            initialMinute = currentMinute.coerceIn(0, 59),
            is24Hour = true
        )
    }

    val formattedTime = TimeFormatUtils.format24Hour(timePickerState.hour, timePickerState.minute)

    val quickPresets = remember(isHebrew) {
        if (isHebrew) {
            listOf(
                "08:00" to "בוקר",
                "12:00" to "צהריים",
                "16:00" to "אחה״צ",
                "18:00" to "ערב",
                "20:00" to "לילה"
            )
        } else {
            listOf(
                "08:00" to "Morning",
                "12:00" to "Noon",
                "16:00" to "Afternoon",
                "18:00" to "Evening",
                "20:00" to "Night"
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 420.dp)
                .padding(vertical = 12.dp)
                .testTag("family_24h_time_picker_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header with title and Clock <-> Numeric Keyboard toggle
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
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Column {
                            Text(
                                text = if (isHebrew) "בחירת שעה (24 שעות)" else "Select Time (24-Hour)",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            Text(
                                text = "$title • $formattedTime",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.testTag("time_picker_header_formatted")
                            )
                        }
                    }

                    // Toggle between Clock Dial and Direct Numeric Input
                    FilledTonalIconButton(
                        onClick = {
                            currentHour = timePickerState.hour
                            currentMinute = timePickerState.minute
                            isNumericInputMode = !isNumericInputMode
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("time_picker_mode_toggle")
                    ) {
                        Icon(
                            imageVector = if (isNumericInputMode) Icons.Default.AccessTime else Icons.Default.Keyboard,
                            contentDescription = if (isNumericInputMode) {
                                if (isHebrew) "מעבר לבחירה בשעון" else "Switch to clock dial"
                            } else {
                                if (isHebrew) "מעבר להקלדת שעה" else "Switch to numeric input"
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Material 3 24-Hour TimePicker or TimeInput (is24Hour = true, no AM/PM)
                if (isNumericInputMode) {
                    TimeInput(
                        state = timePickerState,
                        modifier = Modifier.testTag("m3_time_input_24h")
                    )
                } else {
                    TimePicker(
                        state = timePickerState,
                        modifier = Modifier.testTag("m3_time_picker_24h")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Quick 24h presets row
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(quickPresets) { (timeStr, label) ->
                        val isPresetActive = formattedTime == timeStr
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isPresetActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    val (h, m) = TimeFormatUtils.parse24Hour(timeStr)
                                    currentHour = h
                                    currentMinute = m
                                    presetSeed++
                                }
                                .testTag("time_preset_$timeStr")
                        ) {
                            Text(
                                text = "$label $timeStr",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isPresetActive) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                ),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Clear Hebrew Confirm and Cancel buttons
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
                            .testTag("cancel_time_button")
                    ) {
                        Text(
                            text = if (isHebrew) "ביטול" else "Cancel",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = { onConfirm(formattedTime) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp)
                            .testTag("confirm_time_button")
                    ) {
                        Text(
                            text = if (isHebrew) "אישור ($formattedTime)" else "Confirm ($formattedTime)",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}
