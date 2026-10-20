package com.example.notifications

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.GoogleBlue
import com.example.ui.theme.GoogleBlueDark
import com.example.ui.theme.GoogleBlueLight

/**
 * Full-screen / lock-screen alarm ringing presentation activity.
 * Displays the active alarm time, label, "נודניק" (Snooze), and "כיבוי" (Dismiss).
 */
class FamilyAlarmRingingActivity : ComponentActivity() {

    companion object {
        fun createLaunchIntent(
            context: Context,
            alarmId: String,
            title: String,
            hour: Int,
            minute: Int,
            snoozeMinutes: Int
        ): Intent {
            return Intent(context, FamilyAlarmRingingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_TITLE, title)
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_HOUR, hour)
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_MINUTE, minute)
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_SNOOZE_MINUTES, snoozeMinutes)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreenPresentation()
        enableEdgeToEdge()

        val initialAlarmId = intent?.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_ID).orEmpty()
        val initialTitle = intent?.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_TITLE) ?: "שעון מעורר משפחתי"
        val initialHour = intent?.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_HOUR, 7) ?: 7
        val initialMinute = intent?.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_MINUTE, 0) ?: 0
        val initialSnoozeMin = intent?.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_SNOOZE_MINUTES, 5) ?: 5
        val initialTimeFormatted = String.format(
            java.util.Locale.US,
            "%02d:%02d",
            initialHour.coerceIn(0, 23),
            initialMinute.coerceIn(0, 59)
        )

        setContent {
            val activeState by FamilyAlarmRingingService.activeRingingAlarm.collectAsState()

            // If dismissed from notification or timeout after launch, finish automatically
            LaunchedEffect(activeState) {
                if (activeState == null && !isFinishing) {
                    // Only finish if service is no longer ringing and we already rendered
                }
            }

            val displayTime = activeState?.timeFormatted ?: initialTimeFormatted
            val displayTitle = activeState?.title ?: initialTitle
            val displaySnoozeMinutes = activeState?.snoozeMinutes ?: initialSnoozeMin
            val displayAlarmId = activeState?.alarmId ?: initialAlarmId

            MyApplicationTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    FullScreenAlarmRingingContent(
                        timeFormatted = displayTime,
                        alarmLabel = displayTitle,
                        snoozeMinutes = displaySnoozeMinutes,
                        onSnooze = {
                            if (activeState != null) {
                                FamilyAlarmRingingService.snoozeActiveAlarm(this@FamilyAlarmRingingActivity)
                            } else {
                                FamilyAlarmScheduler.scheduleSnoozeAlarm(
                                    context = this@FamilyAlarmRingingActivity,
                                    alarmId = displayAlarmId.ifBlank { "alarm_snooze" },
                                    title = displayTitle,
                                    soundType = AlarmAudioController.SOUND_MELODY_ENERGETIC,
                                    customSoundUri = "",
                                    vibrateEnabled = true,
                                    snoozeMinutes = displaySnoozeMinutes,
                                    volumePercent = 85
                                )
                                FamilyAlarmRingingService.dismissActiveAlarm(this@FamilyAlarmRingingActivity, displayAlarmId)
                            }
                            finish()
                        },
                        onDismiss = {
                            FamilyAlarmRingingService.dismissActiveAlarm(this@FamilyAlarmRingingActivity, displayAlarmId)
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun configureLockScreenPresentation() {
        runCatching {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                km?.requestDismissKeyguard(this, null)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                )
            }
        }
    }
}

@Composable
fun FullScreenAlarmRingingContent(
    timeFormatted: String,
    alarmLabel: String,
    snoozeMinutes: Int,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0D2346),
                        Color(0xFF1A4B8C),
                        Color(0xFF123262)
                    )
                )
            )
            .systemBarsPadding()
            .padding(24.dp)
            .testTag("full_screen_alarm_ringing_screen"),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = Color.White.copy(alpha = 0.96f),
            tonalElevation = 10.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(1.5.dp, GoogleBlue.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(GoogleBlueLight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Alarm,
                        contentDescription = "שעון מעורר",
                        tint = GoogleBlueDark,
                        modifier = Modifier.size(44.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = GoogleBlueLight.copy(alpha = 0.7f)
                ) {
                    Text(
                        text = "שעון מעורר משפחתי • FamilyTasks",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = GoogleBlueDark
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = timeFormatted,
                    fontSize = 62.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = GoogleBlueDark,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("ringing_alarm_time_text")
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = alarmLabel,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1F2937)
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("ringing_alarm_label_text")
                )

                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(
                        onClick = onSnooze,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = GoogleBlueLight,
                            contentColor = GoogleBlueDark
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 56.dp)
                            .testTag("alarm_ringing_snooze_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Snooze,
                            contentDescription = "נודניק",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = "נודניק (${snoozeMinutes} דק׳)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD93025),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 56.dp)
                            .testTag("alarm_ringing_dismiss_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AlarmOff,
                            contentDescription = "כיבוי",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = "כיבוי",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}
