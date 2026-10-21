package com.example.notifications

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen / lock-screen Sunrise Alarm Ringing Activity.
 *
 * Presents the premium sunrise landscape alarm interface over the lock screen or active app,
 * displaying the scheduled alarm time, custom label (or "בוקר טוב!"), morning message pill,
 * and 3D glossy "נודניק" (Snooze) and "כיבוי" (Dismiss) buttons.
 */
class FamilyAlarmRingingActivity : ComponentActivity() {

    companion object {
        const val DEFAULT_MORNING_LABEL_HE = "בוקר טוב!"
        const val MORNING_PILL_MESSAGE_HE = "☀️ הגיע הזמן להתחיל את היום"

        /**
         * Resolves the user-facing alarm label.
         * Returns the alarm's custom label if provided, or [DEFAULT_MORNING_LABEL_HE] ("בוקר טוב!")
         * when the label is blank or uses an uncustomized placeholder.
         */
        fun resolveAlarmDisplayLabel(rawLabel: String?): String {
            val trimmed = rawLabel?.trim().orEmpty()
            if (trimmed.isBlank()) return DEFAULT_MORNING_LABEL_HE
            if (trimmed == "שעון מעורר" || trimmed == "שעון מעורר משפחתי" || trimmed.equals("Alarm", ignoreCase = true)) {
                return DEFAULT_MORNING_LABEL_HE
            }
            return trimmed
        }

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
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_TITLE, title)
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_HOUR, hour)
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_MINUTE, minute)
                putExtra(FamilyAlarmScheduler.EXTRA_ALARM_SNOOZE_MINUTES, snoozeMinutes)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureLockScreenPresentation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreenPresentation()
        enableEdgeToEdge()

        val initialAlarmId = intent?.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_ID).orEmpty()
        val initialTitle = intent?.getStringExtra(FamilyAlarmScheduler.EXTRA_ALARM_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MORNING_LABEL_HE
        val initialHour = intent?.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_HOUR, 7) ?: 7
        val initialMinute = intent?.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_MINUTE, 0) ?: 0
        val initialSnoozeMin = intent?.getIntExtra(FamilyAlarmScheduler.EXTRA_ALARM_SNOOZE_MINUTES, 5) ?: 5
        val initialTimeFormatted = String.format(
            Locale.US,
            "%02d:%02d",
            initialHour.coerceIn(0, 23),
            initialMinute.coerceIn(0, 59)
        )

        setContent {
            val activeState by FamilyAlarmRingingService.activeRingingAlarm.collectAsState()
            var hasObservedActiveAlarm by remember { mutableStateOf(activeState != null) }

            // If dismissed from notification, remotely deleted, or timed out after launch, finish automatically
            LaunchedEffect(activeState) {
                if (activeState != null) {
                    hasObservedActiveAlarm = true
                } else if (hasObservedActiveAlarm && !isFinishing) {
                    finish()
                }
            }

            val displayTime = activeState?.timeFormatted ?: initialTimeFormatted
            val rawTitle = activeState?.title ?: initialTitle
            val displayTitle = resolveAlarmDisplayLabel(rawTitle)
            val displaySnoozeMinutes = (activeState?.snoozeMinutes ?: initialSnoozeMin).coerceIn(1, 60)
            val displayAlarmId = activeState?.alarmId ?: initialAlarmId
            val displayVibrate = activeState?.vibrateEnabled ?: true

            MyApplicationTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    FullScreenAlarmRingingContent(
                        timeFormatted = displayTime,
                        alarmLabel = displayTitle,
                        snoozeMinutes = displaySnoozeMinutes,
                        vibrateEnabled = displayVibrate,
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
                                    vibrateEnabled = displayVibrate,
                                    snoozeMinutes = displaySnoozeMinutes,
                                    volumePercent = 85
                                )
                                FamilyAlarmRingingService.dismissActiveAlarm(
                                    context = this@FamilyAlarmRingingActivity,
                                    alarmId = displayAlarmId,
                                    cancelPendingSnooze = false
                                )
                            }
                            finish()
                        },
                        onDismiss = {
                            FamilyAlarmRingingService.dismissActiveAlarm(
                                context = this@FamilyAlarmRingingActivity,
                                alarmId = displayAlarmId,
                                cancelPendingSnooze = true
                            )
                            finish()
                        }
                    )
                }
            }
        }
    }

    /**
     * Configures the window so the alarm interface appears directly over the Android lock screen
     * and wakes the display without forcing the user to unlock their PIN/pattern first.
     */
    internal fun configureLockScreenPresentation() {
        runCatching {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
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

/**
 * Full-screen Sunrise Alarm Ringing UI matching the visual design reference:
 * - Separate multi-layered sunrise mountain, alpine lake, golden sun rays, and sky cloud artwork layer.
 * - Top header with twin-bell alarm icon, "שעון מעורר", and "FamilyTasks".
 * - Extra-large white alarm time digits (e.g., 07:00) and dynamic Hebrew alarm label.
 * - Frosted glass morning message pill ("☀️ הגיע הזמן להתחיל את היום").
 * - Two large 3D glossy orb + frosted pedestal buttons for "נודניק" ("עוד X דקות") and "כיבוי" ("הפסקת הצלצול").
 */
@Composable
fun FullScreenAlarmRingingContent(
    timeFormatted: String,
    alarmLabel: String,
    snoozeMinutes: Int,
    vibrateEnabled: Boolean = true,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit
) {
    val resolvedLabel = remember(alarmLabel) {
        FamilyAlarmRingingActivity.resolveAlarmDisplayLabel(alarmLabel)
    }
    val safeSnoozeMinutes = snoozeMinutes.coerceIn(1, 60)
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceAtLeast(1f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("full_screen_alarm_ringing_screen")
    ) {
        val availableWidth = maxWidth
        val isCompactWidth = availableWidth < 360.dp
        val isCompactHeight = maxHeight < 640.dp
        val isVeryShortHeight = maxHeight < 520.dp
        val isLargeFont = fontScale > 1.25f

        // Adaptive typography & button sizing that keeps primary buttons visible without clipping
        val timeFontSizeSp = when {
            isVeryShortHeight -> (54f / fontScale.coerceAtMost(1.35f)).sp
            isCompactHeight || isCompactWidth -> (68f / fontScale.coerceAtMost(1.3f)).sp
            else -> (84f / fontScale.coerceAtMost(1.25f)).sp
        }
        val labelFontSizeSp = when {
            isVeryShortHeight -> 22.sp
            isCompactWidth -> 26.sp
            else -> 32.sp
        }
        val orbSizeDp: Dp = when {
            isVeryShortHeight -> 86.dp
            isCompactHeight -> 104.dp
            isCompactWidth -> 108.dp
            else -> 124.dp
        }

        // Layer 1: Separate full-bleed Sunrise Mountain & Alpine Lake Background Artwork
        SunriseMountainLakeBackground(
            modifier = Modifier
                .fillMaxSize()
                .testTag("alarm_sunrise_background_layer")
        )

        // Layer 2: Interactive Foreground UI respecting cutouts & system bars
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (isCompactWidth) 14.dp else 22.dp,
                    vertical = if (isVeryShortHeight) 10.dp else 20.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Header + Time + Label + Morning Pill Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 540.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(if (isVeryShortHeight) 4.dp else 12.dp))

                // Twin-bell alarm clock header icon
                AlarmHeaderBellClockIcon(
                    modifier = Modifier
                        .size(if (isVeryShortHeight) 46.dp else 58.dp)
                        .testTag("ringing_alarm_header_icon")
                )

                Spacer(modifier = Modifier.height(if (isVeryShortHeight) 4.dp else 8.dp))

                // Header Title: "שעון מעורר"
                Text(
                    text = "שעון מעורר",
                    style = TextStyle(
                        fontSize = if (isVeryShortHeight) 20.sp else 25.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.55f),
                            offset = Offset(0f, 3f),
                            blurRadius = 10f
                        )
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("ringing_alarm_header_title")
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Header Subtitle: "FamilyTasks"
                Text(
                    text = "FamilyTasks",
                    style = TextStyle(
                        fontSize = if (isVeryShortHeight) 13.sp else 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFDCEAFE),
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.5f),
                            offset = Offset(0f, 2f),
                            blurRadius = 8f
                        )
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("ringing_alarm_header_subtitle")
                )

                Spacer(modifier = Modifier.height(if (isVeryShortHeight) 10.dp else 22.dp))

                // Main Alarm Time in very large white digits (rendered LTR so HH:mm never flips)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = timeFormatted,
                        style = TextStyle(
                            fontSize = timeFontSizeSp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = (-1.5).sp,
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.50f),
                                offset = Offset(0f, 6f),
                                blurRadius = 22f
                            )
                        ),
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("ringing_alarm_time_text")
                    )
                }

                Spacer(modifier = Modifier.height(if (isVeryShortHeight) 4.dp else 8.dp))

                // Alarm Label (e.g., "בוקר טוב רועי!" or fallback "בוקר טוב!")
                Text(
                    text = resolvedLabel,
                    style = TextStyle(
                        fontSize = labelFontSizeSp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.60f),
                            offset = Offset(0f, 4f),
                            blurRadius = 14f
                        )
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .testTag("ringing_alarm_label_text")
                )

                Spacer(modifier = Modifier.height(if (isVeryShortHeight) 10.dp else 18.dp))

                // Translucent Glass-Style Morning Pill: "☀️ הגיע הזמן להתחיל את היום"
                MorningGlassMessagePill(
                    text = FamilyAlarmRingingActivity.MORNING_PILL_MESSAGE_HE,
                    isCompact = isVeryShortHeight || isCompactWidth,
                    modifier = Modifier.testTag("ringing_alarm_morning_pill")
                )
            }

            Spacer(modifier = Modifier.height(if (isVeryShortHeight) 14.dp else 28.dp))

            // Bottom Action Section: Two Premium Glossy 3D Buttons (Dismiss & Snooze)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .padding(bottom = if (isVeryShortHeight) 8.dp else 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val useStackedButtons = availableWidth < 290.dp || (isLargeFont && availableWidth < 330.dp)
                if (useStackedButtons) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PremiumGlossyAlarmActionButton(
                            primaryLabel = "נודניק",
                            secondaryLabel = "עוד $safeSnoozeMinutes דקות",
                            isSnoozeStyle = true,
                            orbSize = orbSizeDp,
                            onClick = onSnooze,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("alarm_ringing_snooze_button")
                        )
                        PremiumGlossyAlarmActionButton(
                            primaryLabel = "כיבוי",
                            secondaryLabel = "הפסקת הצלצול",
                            isSnoozeStyle = false,
                            orbSize = orbSizeDp,
                            onClick = onDismiss,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("alarm_ringing_dismiss_button")
                        )
                    }
                } else {
                    // In RTL LayoutDirection, the first item in Row is placed on the RIGHT (Dismiss / Red)
                    // and the second item is placed on the LEFT (Snooze / Blue), matching the reference image.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(if (isCompactWidth) 12.dp else 18.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        PremiumGlossyAlarmActionButton(
                            primaryLabel = "כיבוי",
                            secondaryLabel = "הפסקת הצלצול",
                            isSnoozeStyle = false,
                            orbSize = orbSizeDp,
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("alarm_ringing_dismiss_button")
                        )

                        PremiumGlossyAlarmActionButton(
                            primaryLabel = "נודניק",
                            secondaryLabel = "עוד $safeSnoozeMinutes דקות",
                            isSnoozeStyle = true,
                            orbSize = orbSizeDp,
                            onClick = onSnooze,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("alarm_ringing_snooze_button")
                        )
                    }
                }
            }
        }
    }
}

/**
 * Translucent glass-style message pill displaying "☀️ הגיע הזמן להתחיל את היום".
 */
@Composable
private fun MorningGlassMessagePill(
    text: String,
    isCompact: Boolean,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(50)
    Surface(
        shape = pillShape,
        color = Color.Transparent,
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.50f),
                    Color(0xFFFFE099).copy(alpha = 0.25f)
                )
            )
        ),
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = pillShape,
                ambientColor = Color.Black.copy(alpha = 0.35f),
                spotColor = Color.Black.copy(alpha = 0.35f)
            )
            .clip(pillShape)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0x883D2F24),
                        Color(0x992B2523),
                        Color(0x883D2F24)
                    )
                )
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(
                horizontal = if (isCompact) 16.dp else 22.dp,
                vertical = if (isCompact) 8.dp else 11.dp
            )
        ) {
            Text(
                text = text,
                style = TextStyle(
                    fontSize = if (isCompact) 14.sp else 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.45f),
                        offset = Offset(0f, 2f),
                        blurRadius = 6f
                    )
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Composite 3D glossy orb + frosted dark glass pedestal button inspired by the reference image.
 *
 * - Single-tap anywhere on the orb or pedestal immediately triggers [onClick].
 * - Includes a smooth spring press-scale animation and accessible semantics.
 */
@Composable
private fun PremiumGlossyAlarmActionButton(
    primaryLabel: String,
    secondaryLabel: String,
    isSnoozeStyle: Boolean,
    orbSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "alarm_button_press_scale"
    )

    val pedestalTopOverlap = orbSize * 0.36f
    val pedestalShape = RoundedCornerShape(28.dp)

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .scale(pressScale)
            .defaultMinSize(minWidth = 120.dp, minHeight = orbSize + 64.dp)
            .clip(RoundedCornerShape(32.dp))
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "$primaryLabel - $secondaryLabel"
            }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = Color.White),
                onClick = onClick
            )
            .padding(top = 4.dp, bottom = 4.dp)
    ) {
        // Frosted Dark Glass Pedestal Card at the bottom
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .padding(top = orbSize - pedestalTopOverlap)
                .fillMaxWidth()
                .shadow(
                    elevation = 16.dp,
                    shape = pedestalShape,
                    ambientColor = Color.Black.copy(alpha = 0.65f),
                    spotColor = Color.Black.copy(alpha = 0.65f)
                )
                .clip(pedestalShape)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0x9926292E),
                            Color(0xCC16181C),
                            Color(0xE6101215)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.36f),
                            Color.White.copy(alpha = 0.12f)
                        )
                    ),
                    shape = pedestalShape
                )
                .padding(
                    top = pedestalTopOverlap + 10.dp,
                    bottom = 16.dp,
                    start = 10.dp,
                    end = 10.dp
                )
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = primaryLabel,
                    style = TextStyle(
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.65f),
                            offset = Offset(0f, 2f),
                            blurRadius = 6f
                        )
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = secondaryLabel,
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFD5DCE6),
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.55f),
                            offset = Offset(0f, 1f),
                            blurRadius = 4f
                        )
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 3D Glossy Circular Orb on top of the pedestal
        Glossy3DOrb(
            isSnoozeStyle = isSnoozeStyle,
            orbSize = orbSize
        )
    }
}

/**
 * Renders the 3D glossy glass sphere/orb with outer glow, multi-stop radial depth gradient,
 * top-left specular crescent reflection, bottom rim glow, and crisp white icon.
 */
@Composable
private fun Glossy3DOrb(
    isSnoozeStyle: Boolean,
    orbSize: Dp
) {
    val glowColor = if (isSnoozeStyle) Color(0xFF22B8FF) else Color(0xFFFF4B55)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(orbSize)
            .shadow(
                elevation = 20.dp,
                shape = CircleShape,
                ambientColor = glowColor.copy(alpha = 0.85f),
                spotColor = glowColor.copy(alpha = 0.90f)
            )
            .drawWithCache {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)

                val mainSphereBrush = if (isSnoozeStyle) {
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF4AD8FF),
                            Color(0xFF1792F5),
                            Color(0xFF0A58B8),
                            Color(0xFF053170)
                        ),
                        center = Offset(size.width * 0.36f, size.height * 0.28f),
                        radius = radius * 1.35f
                    )
                } else {
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFF8285),
                            Color(0xFFF03A47),
                            Color(0xFFB81722),
                            Color(0xFF6E0A11)
                        ),
                        center = Offset(size.width * 0.36f, size.height * 0.28f),
                        radius = radius * 1.35f
                    )
                }

                // Inner bottom rim light reflection
                val bottomRimBrush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Transparent,
                        if (isSnoozeStyle) Color(0x9948E5FF) else Color(0x99FF9498)
                    ),
                    startY = size.height * 0.55f,
                    endY = size.height
                )

                // Top glossy specular highlight ellipse
                val topGlossBrush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.72f),
                        Color.White.copy(alpha = 0.22f),
                        Color.Transparent
                    ),
                    startY = size.height * 0.04f,
                    endY = size.height * 0.48f
                )

                // Outer glass rim stroke
                val rimStrokeBrush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.85f),
                        if (isSnoozeStyle) Color(0xFF5CE1FF).copy(alpha = 0.55f) else Color(0xFFFF9EA2).copy(alpha = 0.55f),
                        if (isSnoozeStyle) Color(0xFF29B6F6).copy(alpha = 0.80f) else Color(0xFFFF6E75).copy(alpha = 0.80f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height)
                )

                onDrawBehind {
                    // 1. Soft outer ambient halo
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                glowColor.copy(alpha = 0.42f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = radius * 1.15f
                        ),
                        radius = radius * 1.15f,
                        center = center
                    )

                    // 2. Main 3D sphere body
                    drawCircle(
                        brush = mainSphereBrush,
                        radius = radius * 0.96f,
                        center = center
                    )

                    // 3. Subtle inner shadow around lower-right edge for 3D depth
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.28f)
                            ),
                            center = Offset(size.width * 0.45f, size.height * 0.42f),
                            radius = radius * 0.96f
                        ),
                        radius = radius * 0.96f,
                        center = center
                    )

                    // 4. Bottom inner rim reflection
                    drawCircle(
                        brush = bottomRimBrush,
                        radius = radius * 0.95f,
                        center = center
                    )

                    // 5. Top-left glossy crescent reflection
                    drawOval(
                        brush = topGlossBrush,
                        topLeft = Offset(size.width * 0.16f, size.height * 0.06f),
                        size = Size(size.width * 0.68f, size.height * 0.42f)
                    )

                    // 6. Specular rim border
                    drawCircle(
                        brush = rimStrokeBrush,
                        radius = radius * 0.95f,
                        center = center,
                        style = Stroke(width = 2.2.dp.toPx())
                    )
                }
            }
    ) {
        val iconSize = orbSize * 0.48f
        if (isSnoozeStyle) {
            SnoozeOrbClockZzIcon(modifier = Modifier.size(iconSize))
        } else {
            DismissOrbBellOffIcon(modifier = Modifier.size(iconSize))
        }
    }
}

/**
 * White twin-bell alarm clock icon displayed in the top header.
 */
@Composable
private fun AlarmHeaderBellClockIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(w * 0.5f, h * 0.54f)
        val radius = w * 0.34f
        val strokeWidth = w * 0.085f

        // Drop shadow for legibility against sky
        val shadowOffset = Offset(0f, h * 0.03f)
        val shadowColor = Color.Black.copy(alpha = 0.35f)
        drawCircle(
            color = shadowColor,
            radius = radius,
            center = center + shadowOffset,
            style = Stroke(width = strokeWidth)
        )

        // Left & Right Twin Bells
        drawArc(
            color = Color.White,
            startAngle = 195f,
            sweepAngle = 115f,
            useCenter = false,
            topLeft = Offset(w * 0.06f, h * 0.08f),
            size = Size(w * 0.34f, h * 0.34f),
            style = Stroke(width = strokeWidth * 0.92f, cap = StrokeCap.Round)
        )
        drawArc(
            color = Color.White,
            startAngle = 230f,
            sweepAngle = 115f,
            useCenter = false,
            topLeft = Offset(w * 0.60f, h * 0.08f),
            size = Size(w * 0.34f, h * 0.34f),
            style = Stroke(width = strokeWidth * 0.92f, cap = StrokeCap.Round)
        )

        // Clock body ring
        drawCircle(
            color = Color.White,
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth)
        )

        // Bottom feet
        drawLine(
            color = Color.White,
            start = Offset(w * 0.30f, h * 0.82f),
            end = Offset(w * 0.21f, h * 0.94f),
            strokeWidth = strokeWidth * 0.9f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = Offset(w * 0.70f, h * 0.82f),
            end = Offset(w * 0.79f, h * 0.94f),
            strokeWidth = strokeWidth * 0.9f,
            cap = StrokeCap.Round
        )

        // Clock hands (12:00 and 4:00)
        drawLine(
            color = Color.White,
            start = center,
            end = Offset(center.x, center.y - radius * 0.56f),
            strokeWidth = strokeWidth * 0.82f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = center,
            end = Offset(center.x + radius * 0.44f, center.y + radius * 0.28f),
            strokeWidth = strokeWidth * 0.82f,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Twin-bell alarm clock icon with "zZ" sleep symbols inside the blue Snooze orb.
 */
@Composable
private fun SnoozeOrbClockZzIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val clockCenter = Offset(w * 0.42f, h * 0.58f)
        val radius = w * 0.29f
        val stroke = w * 0.075f

        // Left & right twin bells
        drawArc(
            color = Color.White,
            startAngle = 195f,
            sweepAngle = 115f,
            useCenter = false,
            topLeft = Offset(w * 0.06f, h * 0.18f),
            size = Size(w * 0.28f, h * 0.28f),
            style = Stroke(width = stroke * 0.9f, cap = StrokeCap.Round)
        )
        drawArc(
            color = Color.White,
            startAngle = 230f,
            sweepAngle = 115f,
            useCenter = false,
            topLeft = Offset(w * 0.50f, h * 0.18f),
            size = Size(w * 0.28f, h * 0.28f),
            style = Stroke(width = stroke * 0.9f, cap = StrokeCap.Round)
        )

        // Clock circle
        drawCircle(
            color = Color.White,
            radius = radius,
            center = clockCenter,
            style = Stroke(width = stroke)
        )

        // Clock feet
        drawLine(
            color = Color.White,
            start = Offset(clockCenter.x - radius * 0.62f, clockCenter.y + radius * 0.75f),
            end = Offset(clockCenter.x - radius * 0.88f, h * 0.93f),
            strokeWidth = stroke * 0.88f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = Offset(clockCenter.x + radius * 0.62f, clockCenter.y + radius * 0.75f),
            end = Offset(clockCenter.x + radius * 0.88f, h * 0.93f),
            strokeWidth = stroke * 0.88f,
            cap = StrokeCap.Round
        )

        // Clock hands
        drawLine(
            color = Color.White,
            start = clockCenter,
            end = Offset(clockCenter.x, clockCenter.y - radius * 0.52f),
            strokeWidth = stroke * 0.8f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = clockCenter,
            end = Offset(clockCenter.x - radius * 0.42f, clockCenter.y + radius * 0.32f),
            strokeWidth = stroke * 0.8f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = clockCenter,
            end = Offset(clockCenter.x + radius * 0.42f, clockCenter.y + radius * 0.32f),
            strokeWidth = stroke * 0.8f,
            cap = StrokeCap.Round
        )

        // Small "z"
        val zStroke = stroke * 0.62f
        val z1Path = Path().apply {
            moveTo(w * 0.68f, h * 0.28f)
            lineTo(w * 0.80f, h * 0.28f)
            lineTo(w * 0.68f, h * 0.40f)
            lineTo(w * 0.80f, h * 0.40f)
        }
        drawPath(
            path = z1Path,
            color = Color.White,
            style = Stroke(width = zStroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Larger "Z"
        val z2Path = Path().apply {
            moveTo(w * 0.80f, h * 0.11f)
            lineTo(w * 0.96f, h * 0.11f)
            lineTo(w * 0.80f, h * 0.26f)
            lineTo(w * 0.96f, h * 0.26f)
        }
        drawPath(
            path = z2Path,
            color = Color.White,
            style = Stroke(width = zStroke * 1.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * Bell-off icon with diagonal slash inside the red Dismiss orb.
 */
@Composable
private fun DismissOrbBellOffIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Bell body silhouette
        val bellPath = Path().apply {
            moveTo(w * 0.50f, h * 0.14f)
            cubicTo(
                w * 0.31f, h * 0.14f,
                w * 0.22f, h * 0.29f,
                w * 0.22f, h * 0.50f
            )
            lineTo(w * 0.22f, h * 0.64f)
            lineTo(w * 0.13f, h * 0.74f)
            lineTo(w * 0.87f, h * 0.74f)
            lineTo(w * 0.78f, h * 0.64f)
            lineTo(w * 0.78f, h * 0.50f)
            cubicTo(
                w * 0.78f, h * 0.29f,
                w * 0.69f, h * 0.14f,
                w * 0.50f, h * 0.14f
            )
            close()
        }
        drawPath(path = bellPath, color = Color.White)

        // Top bell finial knob
        drawCircle(
            color = Color.White,
            radius = w * 0.065f,
            center = Offset(w * 0.50f, h * 0.11f)
        )

        // Bottom clapper
        drawArc(
            color = Color.White,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(w * 0.40f, h * 0.72f),
            size = Size(w * 0.20f, h * 0.16f)
        )

        // Diagonal slash with contrasting red cutout edge + white bar
        drawLine(
            color = Color(0xFFB81722),
            start = Offset(w * 0.16f, h * 0.18f),
            end = Offset(w * 0.86f, h * 0.84f),
            strokeWidth = w * 0.14f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = Offset(w * 0.16f, h * 0.18f),
            end = Offset(w * 0.86f, h * 0.84f),
            strokeWidth = w * 0.085f,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Full-bleed procedural Sunrise Mountain & Alpine Lake Background Artwork Layer.
 *
 * Recreates the atmospheric sunrise landscape from the reference design:
 * - Deep zenith blue sky blending into warm golden-amber horizon light.
 * - Sunlit morning clouds with golden-apricot undersides.
 * - Brilliant rising sun on the right horizon with radial bloom and crepuscular rays.
 * - Layered distant misty mountains, mid-ground alpine peaks, reflective golden lake,
 *   and foreground rocky hillside with warm rim lighting and readability scrims.
 */
@Composable
fun SunriseMountainLakeBackground(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val sunCenter = Offset(w * 0.855f, h * 0.525f)

            // 1. Sky gradient from deep morning blue to golden sunrise horizon
            val skyBrush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.00f to Color(0xFF12335C),
                    0.18f to Color(0xFF1F4B7E),
                    0.34f to Color(0xFF3E6C9C),
                    0.44f to Color(0xFF7D8FA4),
                    0.50f to Color(0xFFD99A64),
                    0.55f to Color(0xFFF9C46B),
                    0.62f to Color(0xFF8C6B5D),
                    1.00f to Color(0xFF161A1D)
                ),
                startY = 0f,
                endY = h
            )

            // 2. Golden sun radial glow
            val sunOuterBloom = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to Color(0xFFFFFBE6),
                    0.08f to Color(0xFFFFE58F).copy(alpha = 0.92f),
                    0.22f to Color(0xFFFFAD42).copy(alpha = 0.58f),
                    0.50f to Color(0xFFE87A3E).copy(alpha = 0.22f),
                    1.00f to Color.Transparent
                ),
                center = sunCenter,
                radius = w * 0.85f
            )

            // 3. Distant horizon mountain range
            val farMountainsPath = Path().apply {
                moveTo(0f, h * 0.56f)
                lineTo(w * 0.10f, h * 0.52f)
                lineTo(w * 0.22f, h * 0.535f)
                lineTo(w * 0.38f, h * 0.515f)
                lineTo(w * 0.52f, h * 0.538f)
                lineTo(w * 0.67f, h * 0.548f)
                lineTo(w * 0.79f, h * 0.555f)
                lineTo(w * 0.93f, h * 0.535f)
                lineTo(w, h * 0.542f)
                lineTo(w, h * 0.72f)
                lineTo(0f, h * 0.72f)
                close()
            }

            // 4. Mid-ground dramatic left mountain ridge overlooking the lake
            val leftMountainPath = Path().apply {
                moveTo(0f, h * 0.505f)
                lineTo(w * 0.09f, h * 0.515f)
                lineTo(w * 0.15f, h * 0.54f)
                lineTo(w * 0.26f, h * 0.58f)
                lineTo(w * 0.42f, h * 0.625f)
                lineTo(w * 0.58f, h * 0.655f)
                lineTo(w * 0.58f, h * 0.76f)
                lineTo(0f, h * 0.76f)
                close()
            }

            // 5. Mid-ground right mountain headland framing the lake
            val rightHeadlandPath = Path().apply {
                moveTo(w * 0.52f, h * 0.61f)
                lineTo(w * 0.66f, h * 0.585f)
                lineTo(w * 0.78f, h * 0.575f)
                lineTo(w * 0.92f, h * 0.552f)
                lineTo(w, h * 0.548f)
                lineTo(w, h * 0.64f)
                lineTo(w * 0.85f, h * 0.635f)
                lineTo(w * 0.68f, h * 0.625f)
                close()
            }

            // 6. Alpine lake / fjord surface on the right catching golden sunlight
            val lakePath = Path().apply {
                moveTo(w * 0.42f, h * 0.62f)
                lineTo(w, h * 0.615f)
                lineTo(w, h * 0.75f)
                lineTo(w * 0.38f, h * 0.75f)
                close()
            }

            // 7. Foreground dark silhouetted hillside with rocks and mountain vegetation
            val foregroundHillPath = Path().apply {
                moveTo(0f, h * 0.62f)
                lineTo(w * 0.14f, h * 0.65f)
                lineTo(w * 0.29f, h * 0.69f)
                lineTo(w * 0.48f, h * 0.715f)
                lineTo(w * 0.68f, h * 0.725f)
                lineTo(w * 0.86f, h * 0.695f)
                lineTo(w, h * 0.675f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }

            // 8. Top & bottom readability scrims
            val topScrim = Brush.verticalGradient(
                colors = listOf(
                    Color(0x5907172B),
                    Color(0x2207172B),
                    Color.Transparent
                ),
                startY = 0f,
                endY = h * 0.38f
            )
            val bottomScrim = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0x770C1014),
                    Color(0xCC090C0F)
                ),
                startY = h * 0.66f,
                endY = h
            )

            onDrawBehind {
                // Sky base
                drawRect(brush = skyBrush)

                // Sun outer golden bloom
                drawRect(brush = sunOuterBloom)

                // Scattered morning clouds with dark blue-grey tops and warm golden undersides
                val cloudConfigs = listOf(
                    // (cxRatio, cyRatio, rxRatio, ryRatio, goldAlpha)
                    floatArrayOf(0.10f, 0.08f, 0.18f, 0.028f, 0.45f),
                    floatArrayOf(0.06f, 0.18f, 0.16f, 0.025f, 0.55f),
                    floatArrayOf(0.14f, 0.30f, 0.22f, 0.026f, 0.65f),
                    floatArrayOf(0.88f, 0.14f, 0.20f, 0.028f, 0.60f),
                    floatArrayOf(0.82f, 0.26f, 0.24f, 0.030f, 0.72f),
                    floatArrayOf(0.76f, 0.38f, 0.28f, 0.026f, 0.80f),
                    floatArrayOf(0.28f, 0.42f, 0.25f, 0.022f, 0.70f),
                    floatArrayOf(0.62f, 0.45f, 0.30f, 0.024f, 0.85f)
                )
                cloudConfigs.forEach { cfg ->
                    val cx = w * cfg[0]
                    val cy = h * cfg[1]
                    val rx = w * cfg[2]
                    val ry = h * cfg[3]
                    val goldAlpha = cfg[4]

                    // Warm sunlit underside
                    drawOval(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF425D7D).copy(alpha = 0.45f),
                                Color(0xFFF7B96B).copy(alpha = goldAlpha * 0.65f)
                            ),
                            startY = cy - ry,
                            endY = cy + ry * 1.3f
                        ),
                        topLeft = Offset(cx - rx, cy - ry * 0.8f),
                        size = Size(rx * 2f, ry * 2.1f)
                    )
                }

                // Distant misty mountains
                drawPath(
                    path = farMountainsPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF5D5368),
                            Color(0xFF3E3B4F)
                        ),
                        startY = h * 0.51f,
                        endY = h * 0.65f
                    )
                )

                // Alpine lake with shimmering golden sunrise reflection
                drawPath(
                    path = lakePath,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF3B4B5E),
                            Color(0xFFD89658),
                            Color(0xFFFFD885),
                            Color(0xFFB87D4B)
                        ),
                        startX = w * 0.40f,
                        endX = w
                    )
                )

                // Right mountain headland
                drawPath(
                    path = rightHeadlandPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF59433B),
                            Color(0xFF2E2729)
                        ),
                        startY = h * 0.54f,
                        endY = h * 0.65f
                    )
                )

                // Mid-ground left mountain slope
                drawPath(
                    path = leftMountainPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF2B3B4E),
                            Color(0xFF1F2B38),
                            Color(0xFF18222B)
                        ),
                        startY = h * 0.50f,
                        endY = h * 0.74f
                    )
                )

                // Radiant sun core + crepuscular sunbeams
                val rayAnglesDeg = listOf(145f, 160f, 175f, 195f, 215f, 235f, 260f, 285f, 315f)
                rayAnglesDeg.forEach { angleDeg ->
                    val rad = Math.toRadians(angleDeg.toDouble())
                    val endX = sunCenter.x + (cos(rad) * w * 0.75f).toFloat()
                    val endY = sunCenter.y + (sin(rad) * h * 0.45f).toFloat()
                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0x55FFE49E),
                                Color.Transparent
                            ),
                            start = sunCenter,
                            end = Offset(endX, endY)
                        ),
                        start = sunCenter,
                        end = Offset(endX, endY),
                        strokeWidth = w * 0.045f,
                        cap = StrokeCap.Round
                    )
                }

                // Bright sun disc on the right horizon
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White,
                            Color(0xFFFFF3B0),
                            Color(0xFFFFB74D).copy(alpha = 0f)
                        ),
                        center = sunCenter,
                        radius = w * 0.11f
                    ),
                    radius = w * 0.11f,
                    center = sunCenter
                )

                // Foreground dark rocky hillside & foliage silhouette
                drawPath(
                    path = foregroundHillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF2A311B),
                            Color(0xFF1B2012),
                            Color(0xFF0E110B)
                        ),
                        startY = h * 0.62f,
                        endY = h
                    )
                )

                // Foreground rock boulders on bottom-left catching warm sunrise rim light
                val rockPositions = listOf(
                    floatArrayOf(0.02f, 0.86f, 0.14f, 0.06f),
                    floatArrayOf(0.12f, 0.89f, 0.16f, 0.07f),
                    floatArrayOf(0.24f, 0.92f, 0.15f, 0.06f),
                    floatArrayOf(0.05f, 0.93f, 0.18f, 0.07f)
                )
                rockPositions.forEach { r ->
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF5C4E3C),
                                Color(0xFF231F1A)
                            ),
                            startY = h * r[1],
                            endY = h * (r[1] + r[3])
                        ),
                        topLeft = Offset(w * r[0], h * r[1]),
                        size = Size(w * r[2], h * r[3]),
                        cornerRadius = CornerRadius(18f, 18f)
                    )
                }

                // Top & bottom subtle readability scrims
                drawRect(brush = topScrim)
                drawRect(brush = bottomScrim)
            }
        }
    )
}
