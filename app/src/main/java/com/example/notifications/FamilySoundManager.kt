package com.example.notifications

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

/**
 * Categories in the Velvet Edition Sound Library (48 real synthesized audio files in `res/raw`).
 */
enum class SoundLibraryCategory(
    val titleHe: String,
    val titleEn: String,
    val subtitleHe: String,
    val subtitleEn: String,
    val emoji: String
) {
    FAMILYTASKS_SIGNATURE(
        titleHe = "FamilyTasks Signature",
        titleEn = "FamilyTasks Signature",
        subtitleHe = "צליל החתימה הרשמי של FamilyTasks",
        subtitleEn = "Official FamilyTasks signature sound",
        emoji = "🔷"
    ),
    VELVET_PREMIUM(
        titleHe = "Velvet Premium",
        titleEn = "Velvet Premium",
        subtitleHe = "צלילי קטיפה עשירים ואלגנטיים",
        subtitleEn = "Rich, warm velvet chords & chimes",
        emoji = "💎"
    ),
    PIANO_MELODY(
        titleHe = "Piano & Melody",
        titleEn = "Piano & Melody",
        subtitleHe = "פסנתר אקוסטי, תיבת נגינה ומנגינות",
        subtitleEn = "Acoustic grand piano & celesta melodies",
        emoji = "🎹"
    ),
    NATURE(
        titleHe = "Nature",
        titleEn = "Nature",
        subtitleHe = "ציפורי שחר, פלג הרים, גלי ים ופעמוני רוח",
        subtitleEn = "Dawn birds, mountain stream & ocean waves",
        emoji = "🌿"
    ),
    DIGITAL(
        titleHe = "Digital",
        titleEn = "Digital",
        subtitleHe = "סינת׳ים נקיים, ארפג׳יו וצלילי ממשק מודרניים",
        subtitleEn = "Clean modern synth arpeggios & UI chimes",
        emoji = "⚡"
    ),
    CLASSIC(
        titleHe = "Classic",
        titleEn = "Classic",
        subtitleHe = "שעון פעמונים, וסטמינסטר, חצוצרה ורטרו",
        subtitleEn = "Twin-bell clock, Westminster & vintage chimes",
        emoji = "🕰️"
    ),
    WAKE_UP(
        titleHe = "Wake Up",
        titleEn = "Wake Up",
        subtitleHe = "מנגינות השכמה הדרגתיות ואנרגטיות לבוקר",
        subtitleEn = "Gradual sunrise & energetic morning melodies",
        emoji = "🌅"
    ),
    SOFT_NOTIFICATIONS(
        titleHe = "Soft Notifications",
        titleEn = "Soft Notifications",
        subtitleHe = "צלילים קצרים ועדינים למשימות והתראות",
        subtitleEn = "Short, gentle tones for tasks & reminders",
        emoji = "✨"
    )
}

/**
 * Notification & alarm event types across FamilyTasks that support unified sound selection.
 */
enum class SoundUsageType(
    val prefKey: String,
    val titleHe: String,
    val titleEn: String,
    val subtitleHe: String,
    val subtitleEn: String,
    val defaultSoundId: String,
    val isAlarmStream: Boolean = false,
    val emoji: String = "🔔"
) {
    ALARM_DEFAULT(
        prefKey = "alarm_default",
        titleHe = "שעונים מעוררים (ברירת מחדל)",
        titleEn = "Alarm Clocks (Default)",
        subtitleHe = "צליל ברירת המחדל ביצירת שעון מעורר משפחתי חדש",
        subtitleEn = "Default sound when creating a new family alarm",
        defaultSoundId = "sound_wakeup_sunrise_glow",
        isAlarmStream = true,
        emoji = "⏰"
    ),
    TASK_REMINDER(
        prefKey = "task_reminder",
        titleHe = "תזכורות משימות",
        titleEn = "Task Reminders",
        subtitleHe = "צליל התראה במועד ביצוע משימה שנקבע מראש",
        subtitleEn = "Sound played when a scheduled task reminder triggers",
        defaultSoundId = "sound_soft_reminder_dew",
        isAlarmStream = false,
        emoji = "🔔"
    ),
    TASK_CREATED(
        prefKey = "task_created",
        titleHe = "יצירה והקצאת משימה",
        titleEn = "Task Created & Assigned",
        subtitleHe = "התראה כאשר משימה חדשה נוצרת או מוקצית לבן משפחה",
        subtitleEn = "Notification when a new task is created or assigned",
        defaultSoundId = "familytasks_signature_pulse",
        isAlarmStream = false,
        emoji = "🔷"
    ),
    TASK_UPDATED(
        prefKey = "task_updated",
        titleHe = "עדכון משימה",
        titleEn = "Task Updated",
        subtitleHe = "התראה על שינוי פרטי משימה, שעה או הקצאה מחדש",
        subtitleEn = "Notification when a task is updated or reassigned",
        defaultSoundId = "sound_soft_task_updated",
        isAlarmStream = false,
        emoji = "📝"
    ),
    TASK_COMPLETED(
        prefKey = "task_completed",
        titleHe = "השלמת משימה ונקודות",
        titleEn = "Task Completed & Points",
        subtitleHe = "התראה על סיום משימה, בקשת אישור הורה וקבלת נקודות",
        subtitleEn = "Notification when a task is completed or approved",
        defaultSoundId = "sound_soft_task_completed",
        isAlarmStream = false,
        emoji = "🏆"
    ),
    TASK_DELETED(
        prefKey = "task_deleted",
        titleHe = "מחיקת משימה",
        titleEn = "Task Deleted",
        subtitleHe = "התראה כאשר משימה נמחקת או מבוטלת",
        subtitleEn = "Notification when a task is deleted or cancelled",
        defaultSoundId = "sound_soft_task_deleted",
        isAlarmStream = false,
        emoji = "🗑️"
    ),
    FAMILY_GENERAL(
        prefKey = "family_general",
        titleHe = "התראות משפחתיות נוספות",
        titleEn = "General Family Alerts",
        subtitleHe = "הזמנות למשפחה, עדכוני מערכת שעות והודעות כלליות",
        subtitleEn = "Family invitations, class alerts & general updates",
        defaultSoundId = "sound_soft_family_message",
        isAlarmStream = false,
        emoji = "🏡"
    )
}

/**
 * Represents a real, verified audio asset in `res/raw/`.
 */
data class SoundLibraryItem(
    val id: String,
    val rawResId: Int,
    val category: SoundLibraryCategory,
    val titleHe: String,
    val titleEn: String,
    val subtitleHe: String,
    val subtitleEn: String,
    val durationLabelHe: String,
    val durationLabelEn: String,
    val isAlarmMelody: Boolean,
    val emoji: String = category.emoji
) {
    fun buildResourceUri(context: Context): String {
        return "android.resource://${context.packageName}/$rawResId"
    }
}

/**
 * Persisted sound configuration for a [SoundUsageType] or an individual alarm/reminder.
 */
data class SelectedSoundConfig(
    val soundType: String = "",
    val customSoundUri: String = "",
    val customSoundTitle: String = "",
    val volumePercent: Int = 85,
    val soundId: String = soundType,
    val customUri: String = customSoundUri,
    val customTitle: String = customSoundTitle
)

/**
 * Central manager for the unified FamilyTasks — Velvet Edition sound system.
 *
 * Guarantees:
 * 1. 48 real, royalty-free synthesized `.wav` sounds across 7 categories in `res/raw/`, verified
 *    at runtime before appearing in the UI.
 * 2. Separate persisted default sounds for Alarms, Task Reminders, Task Creation, Task Update,
 *    Task Completion, Task Deletion, and General Family Notifications.
 * 3. Persistent URI permissions + local file copy in both credential-protected and device-protected
 *    storage so custom audio survives app closure and device reboot.
 * 4. Automatic fallback to the default built-in/system sound if a custom audio file is deleted or
 *    its URI becomes inaccessible.
 */
object FamilySoundManager {

    private const val TAG = "FamilySoundManager"
    private const val PREFS_NAME = "family_unified_sound_prefs"
    const val SOUND_PREFIX_LIBRARY = "LIBRARY:"
    const val SOUND_ID_SIGNATURE_PULSE = "familytasks_signature_pulse"

    private val oneShotLock = Any()
    private var activeOneShotPlayer: MediaPlayer? = null

    val allCatalogItems: List<SoundLibraryItem> = listOf(
        // ==================== 0. FAMILYTASKS SIGNATURE (1) ====================
        SoundLibraryItem(
            id = SOUND_ID_SIGNATURE_PULSE,
            rawResId = R.raw.sound_familytasks_signature_pulse,
            category = SoundLibraryCategory.FAMILYTASKS_SIGNATURE,
            titleHe = "FamilyTasks Signature Pulse",
            titleEn = "FamilyTasks Signature Pulse",
            subtitleHe = "צליל החתימה הרשמי של FamilyTasks",
            subtitleEn = "Official FamilyTasks Signature Sound",
            durationLabelHe = "חתימה • 0.7 שנ׳",
            durationLabelEn = "Signature • 0.7s",
            isAlarmMelody = false,
            emoji = "🔷"
        ),

        // ==================== 1. VELVET PREMIUM (7) ====================
        SoundLibraryItem(
            id = "sound_velvet_aurora",
            rawResId = R.raw.sound_velvet_aurora,
            category = SoundLibraryCategory.VELVET_PREMIUM,
            titleHe = "זוהר קטיפתי (Velvet Aurora)",
            titleEn = "Velvet Aurora",
            subtitleHe = "אקורדים חמים ועמוקים בסגנון Velvet",
            subtitleEn = "Warm lush major-9th velvet progression",
            durationLabelHe = "מנגינה • 3.8 שנ׳",
            durationLabelEn = "Melody • 3.8s",
            isAlarmMelody = true,
            emoji = "💎"
        ),
        SoundLibraryItem(
            id = "sound_velvet_sapphire",
            rawResId = R.raw.sound_velvet_sapphire,
            category = SoundLibraryCategory.VELVET_PREMIUM,
            titleHe = "ספיר לילי (Sapphire Dusk)",
            titleEn = "Sapphire Dusk",
            subtitleHe = "פסנתר חשמלי משי ופעמונים רכים",
            subtitleEn = "Silk electric piano & soft bell overtones",
            durationLabelHe = "מנגינה • 3.6 שנ׳",
            durationLabelEn = "Melody • 3.6s",
            isAlarmMelody = true,
            emoji = "💎"
        ),
        SoundLibraryItem(
            id = "sound_velvet_amethyst",
            rawResId = R.raw.sound_velvet_amethyst,
            category = SoundLibraryCategory.VELVET_PREMIUM,
            titleHe = "אמטיסט רך (Amethyst Glow)",
            titleEn = "Amethyst Glow",
            subtitleHe = "ארפג׳יו חלומי ועוטף להשכמה נעימה",
            subtitleEn = "Dreamy harp-pad arpeggio in D major",
            durationLabelHe = "מנגינה • 3.5 שנ׳",
            durationLabelEn = "Melody • 3.5s",
            isAlarmMelody = true,
            emoji = "💎"
        ),
        SoundLibraryItem(
            id = "sound_velvet_silk_chime",
            rawResId = R.raw.sound_velvet_silk_chime,
            category = SoundLibraryCategory.VELVET_PREMIUM,
            titleHe = "פעמון משי (Silk Chime)",
            titleEn = "Silk Chime",
            subtitleHe = "צליל התראה אלגנטי בשני אקורדים",
            subtitleEn = "Refined two-chord velvet notification chime",
            durationLabelHe = "התראה • 1.5 שנ׳",
            durationLabelEn = "Alert • 1.5s",
            isAlarmMelody = false,
            emoji = "💎"
        ),
        SoundLibraryItem(
            id = "sound_velvet_royal_pulse",
            rawResId = R.raw.sound_velvet_royal_pulse,
            category = SoundLibraryCategory.VELVET_PREMIUM,
            titleHe = "פעימת קטיפה (Royal Pulse)",
            titleEn = "Royal Pulse",
            subtitleHe = "מרימבה חמה עם הדהוד קטיפתי",
            subtitleEn = "Warm marimba & harmonic bell cadence",
            durationLabelHe = "התראה • 1.6 שנ׳",
            durationLabelEn = "Alert • 1.6s",
            isAlarmMelody = false,
            emoji = "💎"
        ),
        SoundLibraryItem(
            id = "sound_velvet_crystal_drop",
            rawResId = R.raw.sound_velvet_crystal_drop,
            category = SoundLibraryCategory.VELVET_PREMIUM,
            titleHe = "טיפת בדולח (Crystal Drop)",
            titleEn = "Crystal Drop",
            subtitleHe = "מרווח זכוכית צלול ונקי לתזכורת",
            subtitleEn = "Pure glass-harmonic fifth interval",
            durationLabelHe = "התראה • 1.3 שנ׳",
            durationLabelEn = "Alert • 1.3s",
            isAlarmMelody = false,
            emoji = "💎"
        ),
        SoundLibraryItem(
            id = "sound_velvet_breeze",
            rawResId = R.raw.sound_velvet_breeze,
            category = SoundLibraryCategory.VELVET_PREMIUM,
            titleHe = "משב קטיפה (Velvet Breeze)",
            titleEn = "Velvet Breeze",
            subtitleHe = "טריאדת ג׳אז חמה בעלייה עדינה",
            subtitleEn = "Ascending warm jazz triad with satin decay",
            durationLabelHe = "התראה • 1.8 שנ׳",
            durationLabelEn = "Alert • 1.8s",
            isAlarmMelody = false,
            emoji = "💎"
        ),

        // ==================== 2. PIANO & MELODY (7) ====================
        SoundLibraryItem(
            id = "sound_piano_morning_sonata",
            rawResId = R.raw.sound_piano_morning_sonata,
            category = SoundLibraryCategory.PIANO_MELODY,
            titleHe = "סונטת בוקר (Morning Sonata)",
            titleEn = "Morning Sonata",
            subtitleHe = "מנגינת פסנתר כנף בהירה ומעוררת",
            subtitleEn = "Expressive acoustic grand piano melody",
            durationLabelHe = "מנגינה • 4.0 שנ׳",
            durationLabelEn = "Melody • 4.0s",
            isAlarmMelody = true,
            emoji = "🎹"
        ),
        SoundLibraryItem(
            id = "sound_piano_waltz_dream",
            rawResId = R.raw.sound_piano_waltz_dream,
            category = SoundLibraryCategory.PIANO_MELODY,
            titleHe = "ואלס חלומי (Dream Waltz)",
            titleEn = "Dream Waltz",
            subtitleHe = "ואלס פסנתר קלאסי במקצב שלושה רבעים",
            subtitleEn = "Graceful 3/4 acoustic piano waltz",
            durationLabelHe = "מנגינה • 3.9 שנ׳",
            durationLabelEn = "Melody • 3.9s",
            isAlarmMelody = true,
            emoji = "🎹"
        ),
        SoundLibraryItem(
            id = "sound_piano_nocturne_calm",
            rawResId = R.raw.sound_piano_nocturne_calm,
            category = SoundLibraryCategory.PIANO_MELODY,
            titleHe = "נוקטורן שליו (Calm Nocturne)",
            titleEn = "Calm Nocturne",
            subtitleHe = "ארפג׳יו פסנתר לירי ומרגיע",
            subtitleEn = "Gentle lyrical piano arpeggio",
            durationLabelHe = "מנגינה • 4.0 שנ׳",
            durationLabelEn = "Melody • 4.0s",
            isAlarmMelody = true,
            emoji = "🎹"
        ),
        SoundLibraryItem(
            id = "sound_piano_music_box",
            rawResId = R.raw.sound_piano_music_box,
            category = SoundLibraryCategory.PIANO_MELODY,
            titleHe = "תיבת נגינה (Celesta Music Box)",
            titleEn = "Celesta Music Box",
            subtitleHe = "מנגינת צ׳לסטה קסומה בסגנון תיבת נגינה",
            subtitleEn = "Sparkling celesta music-box melody",
            durationLabelHe = "מנגינה • 3.6 שנ׳",
            durationLabelEn = "Melody • 3.6s",
            isAlarmMelody = true,
            emoji = "🎼"
        ),
        SoundLibraryItem(
            id = "sound_piano_warm_prelude",
            rawResId = R.raw.sound_piano_warm_prelude,
            category = SoundLibraryCategory.PIANO_MELODY,
            titleHe = "פרלוד חם (Warm Prelude)",
            titleEn = "Warm Prelude",
            subtitleHe = "פרלוד פסנתר הרמוני בהשראת באך",
            subtitleEn = "Harmonic ascending piano prelude",
            durationLabelHe = "מנגינה • 3.8 שנ׳",
            durationLabelEn = "Melody • 3.8s",
            isAlarmMelody = true,
            emoji = "🎹"
        ),
        SoundLibraryItem(
            id = "sound_piano_chord_bloom",
            rawResId = R.raw.sound_piano_chord_bloom,
            category = SoundLibraryCategory.PIANO_MELODY,
            titleHe = "אקורד פסנתר (Piano Bloom)",
            titleEn = "Piano Bloom",
            subtitleHe = "פריטת אקורד פסנתר עשירה לתזכורות",
            subtitleEn = "Rich grand piano rolled major-7th chord",
            durationLabelHe = "התראה • 1.6 שנ׳",
            durationLabelEn = "Alert • 1.6s",
            isAlarmMelody = false,
            emoji = "🎹"
        ),
        SoundLibraryItem(
            id = "sound_piano_gentle_motif",
            rawResId = R.raw.sound_piano_gentle_motif,
            category = SoundLibraryCategory.PIANO_MELODY,
            titleHe = "מוטיב פסנתר עדין (Gentle Motif)",
            titleEn = "Gentle Motif",
            subtitleHe = "שלושה תווי פסנתר נקיים להתראה",
            subtitleEn = "Three-note acoustic piano phrase",
            durationLabelHe = "התראה • 1.4 שנ׳",
            durationLabelEn = "Alert • 1.4s",
            isAlarmMelody = false,
            emoji = "🎹"
        ),

        // ==================== 3. NATURE (7) ====================
        SoundLibraryItem(
            id = "sound_nature_forest_birds",
            rawResId = R.raw.sound_nature_forest_birds,
            category = SoundLibraryCategory.NATURE,
            titleHe = "ציפורי יער בשחר (Forest Dawn Birds)",
            titleEn = "Forest Dawn Birds",
            subtitleHe = "שירת ציפורים טבעית עם אוושת בוקר רכה",
            subtitleEn = "Melodic songbird warbles at sunrise",
            durationLabelHe = "מנגינה • 3.8 שנ׳",
            durationLabelEn = "Melody • 3.8s",
            isAlarmMelody = true,
            emoji = "🐦"
        ),
        SoundLibraryItem(
            id = "sound_nature_mountain_stream",
            rawResId = R.raw.sound_nature_mountain_stream,
            category = SoundLibraryCategory.NATURE,
            titleHe = "פלג הרים זורם (Mountain Stream)",
            titleEn = "Mountain Stream",
            subtitleHe = "צלילי קלימבה ופעמוני מים פנטטוניים",
            subtitleEn = "Pentatonic water droplets & bamboo chimes",
            durationLabelHe = "מנגינה • 3.8 שנ׳",
            durationLabelEn = "Melody • 3.8s",
            isAlarmMelody = true,
            emoji = "🏞️"
        ),
        SoundLibraryItem(
            id = "sound_nature_ocean_waves",
            rawResId = R.raw.sound_nature_ocean_waves,
            category = SoundLibraryCategory.NATURE,
            titleHe = "גלי ים שקטים (Calm Ocean Waves)",
            titleEn = "Calm Ocean Waves",
            subtitleHe = "אוושת גלי חוף עם תהודה הרמונית מרגיעה",
            subtitleEn = "Soothing ocean surf swell & harmonic shell tone",
            durationLabelHe = "מנגינה • 4.0 שנ׳",
            durationLabelEn = "Melody • 4.0s",
            isAlarmMelody = true,
            emoji = "🌊"
        ),
        SoundLibraryItem(
            id = "sound_nature_wind_chimes",
            rawResId = R.raw.sound_nature_wind_chimes,
            category = SoundLibraryCategory.NATURE,
            titleHe = "פעמוני רוח בגן (Garden Wind Chimes)",
            titleEn = "Garden Wind Chimes",
            subtitleHe = "פעמוני רוח מתכתיים המנצנצים ברוח",
            subtitleEn = "Shimmering pentatonic wind-chime cascade",
            durationLabelHe = "מנגינה • 3.6 שנ׳",
            durationLabelEn = "Melody • 3.6s",
            isAlarmMelody = true,
            emoji = "🎐"
        ),
        SoundLibraryItem(
            id = "sound_nature_raindrop_zen",
            rawResId = R.raw.sound_nature_raindrop_zen,
            category = SoundLibraryCategory.NATURE,
            titleHe = "טיפות גשם זן (Zen Raindrops)",
            titleEn = "Zen Raindrops",
            subtitleHe = "טיפות מים מהדהדות על קערות עץ",
            subtitleEn = "Resonant water-drop plucks on wooden bowls",
            durationLabelHe = "מנגינה • 3.4 שנ׳",
            durationLabelEn = "Melody • 3.4s",
            isAlarmMelody = true,
            emoji = "🌧️"
        ),
        SoundLibraryItem(
            id = "sound_nature_bamboo_tap",
            rawResId = R.raw.sound_nature_bamboo_tap,
            category = SoundLibraryCategory.NATURE,
            titleHe = "נקישת במבוק (Bamboo Echo)",
            titleEn = "Bamboo Echo",
            subtitleHe = "נקישת במבוק טבעית וקצרה להתראות",
            subtitleEn = "Hollow wooden bamboo percussion alert",
            durationLabelHe = "התראה • 1.4 שנ׳",
            durationLabelEn = "Alert • 1.4s",
            isAlarmMelody = false,
            emoji = "🎋"
        ),
        SoundLibraryItem(
            id = "sound_nature_bird_chirp",
            rawResId = R.raw.sound_nature_bird_chirp,
            category = SoundLibraryCategory.NATURE,
            titleHe = "ציוץ בוקר קצר (Morning Robin)",
            titleEn = "Morning Robin",
            subtitleHe = "ציוץ ציפור כפול ועדין לתזכורת משימה",
            subtitleEn = "Double melodic bird whistle for notifications",
            durationLabelHe = "התראה • 1.3 שנ׳",
            durationLabelEn = "Alert • 1.3s",
            isAlarmMelody = false,
            emoji = "🐦"
        ),

        // ==================== 4. DIGITAL (7) ====================
        SoundLibraryItem(
            id = "sound_digital_neon_pulse",
            rawResId = R.raw.sound_digital_neon_pulse,
            category = SoundLibraryCategory.DIGITAL,
            titleHe = "פולס ניאון (Neon Pulse)",
            titleEn = "Neon Pulse",
            subtitleHe = "ארפג׳יו סינת׳ FM חד ומודרני",
            subtitleEn = "Crisp modern FM synth arpeggio",
            durationLabelHe = "מנגינה • 3.5 שנ׳",
            durationLabelEn = "Melody • 3.5s",
            isAlarmMelody = true,
            emoji = "⚡"
        ),
        SoundLibraryItem(
            id = "sound_digital_cyber_harp",
            rawResId = R.raw.sound_digital_cyber_harp,
            category = SoundLibraryCategory.DIGITAL,
            titleHe = "נבל דיגיטלי (Cyber Harp)",
            titleEn = "Cyber Harp",
            subtitleHe = "פריטת מיתרים אלקטרונית נקייה",
            subtitleEn = "Plucked synth wave harmonic sequence",
            durationLabelHe = "מנגינה • 3.6 שנ׳",
            durationLabelEn = "Melody • 3.6s",
            isAlarmMelody = true,
            emoji = "🎛️"
        ),
        SoundLibraryItem(
            id = "sound_digital_starlight",
            rawResId = R.raw.sound_digital_starlight,
            category = SoundLibraryCategory.DIGITAL,
            titleHe = "אור כוכבים (Starlight Synth)",
            titleEn = "Starlight Synth",
            subtitleHe = "פעמונים דיגיטליים מנצנצים באוקטבות",
            subtitleEn = "Shimmering octave bell sequence",
            durationLabelHe = "מנגינה • 3.5 שנ׳",
            durationLabelEn = "Melody • 3.5s",
            isAlarmMelody = true,
            emoji = "🌟"
        ),
        SoundLibraryItem(
            id = "sound_digital_quantum_beep",
            rawResId = R.raw.sound_digital_quantum_beep,
            category = SoundLibraryCategory.DIGITAL,
            titleHe = "ביפ קוונטי (Quantum Beep)",
            titleEn = "Quantum Beep",
            subtitleHe = "שעון מעורר דיגיטלי קצבי ומדויק",
            subtitleEn = "Clean rhythmic modern digital alarm pattern",
            durationLabelHe = "מנגינה • 3.2 שנ׳",
            durationLabelEn = "Melody • 3.2s",
            isAlarmMelody = true,
            emoji = "📟"
        ),
        SoundLibraryItem(
            id = "sound_digital_spark_ping",
            rawResId = R.raw.sound_digital_spark_ping,
            category = SoundLibraryCategory.DIGITAL,
            titleHe = "פינג ניצוץ (Spark Ping)",
            titleEn = "Spark Ping",
            subtitleHe = "צליל התראה עתידני קצר וחד",
            subtitleEn = "Fast futuristic UI notification sparkle",
            durationLabelHe = "התראה • 1.2 שנ׳",
            durationLabelEn = "Alert • 1.2s",
            isAlarmMelody = false,
            emoji = "⚡"
        ),
        SoundLibraryItem(
            id = "sound_digital_prism_alert",
            rawResId = R.raw.sound_digital_prism_alert,
            category = SoundLibraryCategory.DIGITAL,
            titleHe = "התראת פריזמה (Prism Alert)",
            titleEn = "Prism Alert",
            subtitleHe = "שלוש מדרגות קריסטל דיגיטליות",
            subtitleEn = "Three-step crystal synth triad",
            durationLabelHe = "התראה • 1.4 שנ׳",
            durationLabelEn = "Alert • 1.4s",
            isAlarmMelody = false,
            emoji = "🔷"
        ),
        SoundLibraryItem(
            id = "sound_digital_sonar_chime",
            rawResId = R.raw.sound_digital_sonar_chime,
            category = SoundLibraryCategory.DIGITAL,
            titleHe = "סונאר רך (Soft Sonar)",
            titleEn = "Soft Sonar",
            subtitleHe = "הד דיגיטלי חם עם שובל הרמוני",
            subtitleEn = "Warm resonant sonar ping with harmonic tail",
            durationLabelHe = "התראה • 1.5 שנ׳",
            durationLabelEn = "Alert • 1.5s",
            isAlarmMelody = false,
            emoji = "📡"
        ),

        // ==================== 5. CLASSIC (7) ====================
        SoundLibraryItem(
            id = "sound_classic_twin_bell",
            rawResId = R.raw.sound_classic_twin_bell,
            category = SoundLibraryCategory.CLASSIC,
            titleHe = "שעון פעמונים קלאסי (Twin Bell Clock)",
            titleEn = "Twin Bell Clock",
            subtitleHe = "צלצול שעון מעורר מכני קלאסי וחזק",
            subtitleEn = "Authentic mechanical twin-bell alarm ring",
            durationLabelHe = "מנגינה • 3.6 שנ׳",
            durationLabelEn = "Melody • 3.6s",
            isAlarmMelody = true,
            emoji = "⏰"
        ),
        SoundLibraryItem(
            id = "sound_classic_westminster",
            rawResId = R.raw.sound_classic_westminster,
            category = SoundLibraryCategory.CLASSIC,
            titleHe = "פעמוני וסטמינסטר (Westminster Quarters)",
            titleEn = "Westminster Quarters",
            subtitleHe = "מנגינת ארבעת הפעמונים המפורסמת",
            subtitleEn = "Iconic 4-note cathedral clock chime",
            durationLabelHe = "מנגינה • 4.0 שנ׳",
            durationLabelEn = "Melody • 4.0s",
            isAlarmMelody = true,
            emoji = "🕰️"
        ),
        SoundLibraryItem(
            id = "sound_classic_vintage_brass",
            rawResId = R.raw.sound_classic_vintage_brass,
            category = SoundLibraryCategory.CLASSIC,
            titleHe = "חצוצרת בוקר קלאסית (Vintage Fanfare)",
            titleEn = "Vintage Fanfare",
            subtitleHe = "תרועת כלי נשיפה חמה להשכמה",
            subtitleEn = "Warm brass horn triad wake-up call",
            durationLabelHe = "מנגינה • 3.5 שנ׳",
            durationLabelEn = "Melody • 3.5s",
            isAlarmMelody = true,
            emoji = "🎺"
        ),
        SoundLibraryItem(
            id = "sound_classic_grandfather_clock",
            rawResId = R.raw.sound_classic_grandfather_clock,
            category = SoundLibraryCategory.CLASSIC,
            titleHe = "שעון מטוטלת עתיק (Grandfather Clock)",
            titleEn = "Grandfather Clock",
            subtitleHe = "תקתוק מטוטלת וצלצול ברונזה עמוק",
            subtitleEn = "Tick-tock rhythm & deep bronze chime",
            durationLabelHe = "מנגינה • 3.8 שנ׳",
            durationLabelEn = "Melody • 3.8s",
            isAlarmMelody = true,
            emoji = "🕰️"
        ),
        SoundLibraryItem(
            id = "sound_classic_retro_beeper",
            rawResId = R.raw.sound_classic_retro_beeper,
            category = SoundLibraryCategory.CLASSIC,
            titleHe = "שעון דיגיטלי רטרו (80s Quartz Beeper)",
            titleEn = "80s Quartz Beeper",
            subtitleHe = "צפצוף שעון קוורץ קלאסי משנות ה־80",
            subtitleEn = "Classic quartz watch beep-beep-beep pattern",
            durationLabelHe = "מנגינה • 3.2 שנ׳",
            durationLabelEn = "Melody • 3.2s",
            isAlarmMelody = true,
            emoji = "⌚"
        ),
        SoundLibraryItem(
            id = "sound_classic_doorbell_ding",
            rawResId = R.raw.sound_classic_doorbell_ding,
            category = SoundLibraryCategory.CLASSIC,
            titleHe = "דינג-דונג קלאסי (Classic Ding-Dong)",
            titleEn = "Classic Ding-Dong",
            subtitleHe = "פעמון שני תווים קלאסי וברור",
            subtitleEn = "Two-tone tubular bell chime for reminders",
            durationLabelHe = "התראה • 1.6 שנ׳",
            durationLabelEn = "Alert • 1.6s",
            isAlarmMelody = false,
            emoji = "🔔"
        ),
        SoundLibraryItem(
            id = "sound_classic_desk_bell",
            rawResId = R.raw.sound_classic_desk_bell,
            category = SoundLibraryCategory.CLASSIC,
            titleHe = "פעמון שולחן (Silver Desk Bell)",
            titleEn = "Silver Desk Bell",
            subtitleHe = "נקישת פעמון כסף צלולה עם הדהוד ארוך",
            subtitleEn = "Clear silver bell tap with long shimmer",
            durationLabelHe = "התראה • 1.3 שנ׳",
            durationLabelEn = "Alert • 1.3s",
            isAlarmMelody = false,
            emoji = "🛎️"
        ),

        // ==================== 6. WAKE UP (7) ====================
        SoundLibraryItem(
            id = "sound_wakeup_sunrise_glow",
            rawResId = R.raw.sound_wakeup_sunrise_glow,
            category = SoundLibraryCategory.WAKE_UP,
            titleHe = "זריחה הדרגתית (Sunrise Crescendo)",
            titleEn = "Sunrise Crescendo",
            subtitleHe = "מנגינה המתגברת בעדינות עם קרני הבוקר",
            subtitleEn = "Gradually brightening marimba & pad melody",
            durationLabelHe = "מנגינה • 4.0 שנ׳",
            durationLabelEn = "Melody • 4.0s",
            isAlarmMelody = true,
            emoji = "🌅"
        ),
        SoundLibraryItem(
            id = "sound_wakeup_happy_family",
            rawResId = R.raw.sound_wakeup_happy_family,
            category = SoundLibraryCategory.WAKE_UP,
            titleHe = "בוקר משפחתי שמח (Happy Family Morning)",
            titleEn = "Happy Family Morning",
            subtitleHe = "מנגינת קסילופון ופסנתר קופצנית ומחייכת",
            subtitleEn = "Cheerful bouncy xylophone & piano tune",
            durationLabelHe = "מנגינה • 3.8 שנ׳",
            durationLabelEn = "Melody • 3.8s",
            isAlarmMelody = true,
            emoji = "☀️"
        ),
        SoundLibraryItem(
            id = "sound_wakeup_energetic_start",
            rawResId = R.raw.sound_wakeup_energetic_start,
            category = SoundLibraryCategory.WAKE_UP,
            titleHe = "פתיחת יום אנרגטית (Energetic Start)",
            titleEn = "Energetic Start",
            subtitleHe = "קצב מעורר וממריץ להתחלת יום פעיל",
            subtitleEn = "Upbeat syncopated brass & bell rhythm",
            durationLabelHe = "מנגינה • 3.8 שנ׳",
            durationLabelEn = "Melody • 3.8s",
            isAlarmMelody = true,
            emoji = "🚀"
        ),
        SoundLibraryItem(
            id = "sound_wakeup_champion_fanfare",
            rawResId = R.raw.sound_wakeup_champion_fanfare,
            category = SoundLibraryCategory.WAKE_UP,
            titleHe = "תרועת אלופים (Champion Fanfare)",
            titleEn = "Champion Fanfare",
            subtitleHe = "תרועת ניצחון חגיגית להשכמת בוקר",
            subtitleEn = "Triumphant ascending horn & chime fanfare",
            durationLabelHe = "מנגינה • 3.6 שנ׳",
            durationLabelEn = "Melody • 3.6s",
            isAlarmMelody = true,
            emoji = "🏆"
        ),
        SoundLibraryItem(
            id = "sound_wakeup_gentle_breeze",
            rawResId = R.raw.sound_wakeup_gentle_breeze,
            category = SoundLibraryCategory.WAKE_UP,
            titleHe = "יקיצה רכה (Gentle Awakening)",
            titleEn = "Gentle Awakening",
            subtitleHe = "קלימבה חמה והרמוניה רגועה ללא בהלה",
            subtitleEn = "Soft kalimba & warm harmonic awakening",
            durationLabelHe = "מנגינה • 3.8 שנ׳",
            durationLabelEn = "Melody • 3.8s",
            isAlarmMelody = true,
            emoji = "🌸"
        ),
        SoundLibraryItem(
            id = "sound_wakeup_cuckoo_morning",
            rawResId = R.raw.sound_wakeup_cuckoo_morning,
            category = SoundLibraryCategory.WAKE_UP,
            titleHe = "שעון קוקייה עליז (Morning Cuckoo)",
            titleEn = "Morning Cuckoo",
            subtitleHe = "קריאת קוקייה מוזיקלית בליווי מיתרים",
            subtitleEn = "Playful cuckoo intervals with acoustic pluck",
            durationLabelHe = "מנגינה • 3.5 שנ׳",
            durationLabelEn = "Melody • 3.5s",
            isAlarmMelody = true,
            emoji = "🐦"
        ),
        SoundLibraryItem(
            id = "sound_wakeup_espresso_beat",
            rawResId = R.raw.sound_wakeup_espresso_beat,
            category = SoundLibraryCategory.WAKE_UP,
            titleHe = "קצב אספרסו (Espresso Rhythm)",
            titleEn = "Espresso Rhythm",
            subtitleHe = "גרוב מרימבה מהיר ומרענן לבוקר",
            subtitleEn = "Lively marimba groove for fast wake-up",
            durationLabelHe = "מנגינה • 3.6 שנ׳",
            durationLabelEn = "Melody • 3.6s",
            isAlarmMelody = true,
            emoji = "☕"
        ),

        // ==================== 7. SOFT NOTIFICATIONS (6) ====================
        SoundLibraryItem(
            id = "sound_soft_task_created",
            rawResId = R.raw.sound_soft_task_created,
            category = SoundLibraryCategory.SOFT_NOTIFICATIONS,
            titleHe = "משימה חדשה (Soft Task Pop)",
            titleEn = "Soft Task Pop",
            subtitleHe = "צליל קלימבה כפול בעלייה רכה למשימה חדשה",
            subtitleEn = "Gentle warm marimba double-note upward interval",
            durationLabelHe = "התראה • 1.1 שנ׳",
            durationLabelEn = "Alert • 1.1s",
            isAlarmMelody = false,
            emoji = "📌"
        ),
        SoundLibraryItem(
            id = "sound_soft_task_completed",
            rawResId = R.raw.sound_soft_task_completed,
            category = SoundLibraryCategory.SOFT_NOTIFICATIONS,
            titleHe = "השלמת משימה ונקודות (Victory Chime)",
            titleEn = "Victory Chime",
            subtitleHe = "אקורד הצלחה מנצנץ בסיום משימה",
            subtitleEn = "Joyful sparkling 4-note ascending success chord",
            durationLabelHe = "התראה • 1.5 שנ׳",
            durationLabelEn = "Alert • 1.5s",
            isAlarmMelody = false,
            emoji = "⭐"
        ),
        SoundLibraryItem(
            id = "sound_soft_task_updated",
            rawResId = R.raw.sound_soft_task_updated,
            category = SoundLibraryCategory.SOFT_NOTIFICATIONS,
            titleHe = "עדכון משימה (Subtle Update)",
            titleEn = "Subtle Update",
            subtitleHe = "נצנוץ קטיפתי קצר לעדכון משימה",
            subtitleEn = "Soft Rhodes two-note shimmer",
            durationLabelHe = "התראה • 1.1 שנ׳",
            durationLabelEn = "Alert • 1.1s",
            isAlarmMelody = false,
            emoji = "🔄"
        ),
        SoundLibraryItem(
            id = "sound_soft_task_deleted",
            rawResId = R.raw.sound_soft_task_deleted,
            category = SoundLibraryCategory.SOFT_NOTIFICATIONS,
            titleHe = "מחיקה / ביטול רך (Gentle Dismiss)",
            titleEn = "Gentle Dismiss",
            subtitleHe = "מרווח עץ יורד ועדין למחיקה או ביטול",
            subtitleEn = "Soft descending warm wood-chime interval",
            durationLabelHe = "התראה • 1.0 שנ׳",
            durationLabelEn = "Alert • 1.0s",
            isAlarmMelody = false,
            emoji = "🍃"
        ),
        SoundLibraryItem(
            id = "sound_soft_family_message",
            rawResId = R.raw.sound_soft_family_message,
            category = SoundLibraryCategory.SOFT_NOTIFICATIONS,
            titleHe = "התראה משפחתית (Family Whisper)",
            titleEn = "Family Whisper",
            subtitleHe = "שלושה תווים חמים לעדכוני משפחה",
            subtitleEn = "Warm three-note kalimba greeting",
            durationLabelHe = "התראה • 1.3 שנ׳",
            durationLabelEn = "Alert • 1.3s",
            isAlarmMelody = false,
            emoji = "👨‍👩‍👧‍👦"
        ),
        SoundLibraryItem(
            id = "sound_soft_reminder_dew",
            rawResId = R.raw.sound_soft_reminder_dew,
            category = SoundLibraryCategory.SOFT_NOTIFICATIONS,
            titleHe = "טל תזכורת (Reminder Dewdrop)",
            titleEn = "Reminder Dewdrop",
            subtitleHe = "פעמון קריסטל עדין לתזכורת ביצוע משימה",
            subtitleEn = "Delicate crystal bell with warm pad tail",
            durationLabelHe = "התראה • 1.4 שנ׳",
            durationLabelEn = "Alert • 1.4s",
            isAlarmMelody = false,
            emoji = "💧"
        )
    )

    private val _usageConfigsFlow = MutableStateFlow<Map<SoundUsageType, SelectedSoundConfig>>(emptyMap())
    val usageConfigsFlow: StateFlow<Map<SoundUsageType, SelectedSoundConfig>> = _usageConfigsFlow.asStateFlow()

    /**
     * Returns only library sounds whose raw audio file genuinely exists and can be opened in `res/raw/`.
     * Never returns a placeholder or missing audio resource.
     */
    fun getVerifiedLibrarySounds(
        context: Context,
        category: SoundLibraryCategory? = null
    ): List<SoundLibraryItem> {
        val filtered = if (category != null) {
            allCatalogItems.filter { it.category == category }
        } else {
            allCatalogItems
        }
        return filtered.filter { item -> isRawSoundResourceValid(context, item.rawResId) }
    }

    fun findLibrarySoundById(context: Context, soundId: String): SoundLibraryItem? {
        val normalized = soundId.removePrefix(SOUND_PREFIX_LIBRARY).trim()
        val item = allCatalogItems.find { it.id == normalized } ?: return null
        return if (isRawSoundResourceValid(context, item.rawResId)) item else null
    }

    fun findLibrarySoundByUri(context: Context, uriString: String): SoundLibraryItem? {
        if (uriString.isBlank()) return null
        val pkg = context.packageName
        return allCatalogItems.find { item ->
            val resEntryName = runCatching { context.resources.getResourceEntryName(item.rawResId) }.getOrNull().orEmpty()
            val expectedUri = "android.resource://$pkg/${item.rawResId}"
            val expectedByName = "android.resource://$pkg/raw/${item.id}"
            val expectedByResName = if (resEntryName.isNotBlank()) "android.resource://$pkg/raw/$resEntryName" else ""
            (uriString == expectedUri ||
                uriString == expectedByName ||
                (expectedByResName.isNotBlank() && uriString == expectedByResName) ||
                uriString.endsWith("/${item.rawResId}") ||
                (resEntryName.isNotBlank() && uriString.endsWith("/$resEntryName"))) &&
                isRawSoundResourceValid(context, item.rawResId)
        }
    }

    fun isRawSoundResourceValid(context: Context, rawResId: Int): Boolean {
        if (rawResId == 0) return false
        return runCatching {
            context.resources.openRawResourceFd(rawResId)?.use { fd ->
                fd.length > 44L
            } ?: context.resources.openRawResource(rawResId).use { stream ->
                stream.available() > 44
            }
        }.getOrDefault(false)
    }

    /**
     * Checks whether an arbitrary URI (`file://`, `content://`, or `android.resource://`)
     * is currently readable and non-empty.
     */
    fun isUriAccessibleAndValid(context: Context, uriString: String): Boolean {
        if (uriString.isBlank()) return false
        return runCatching {
            val uri = Uri.parse(uriString)
            when (uri.scheme?.lowercase()) {
                "file" -> {
                    val path = uri.path ?: return false
                    val f = File(path)
                    if (f.exists() && f.canRead() && f.length() > 44L) {
                        return true
                    }
                    // Check device-protected mirror if file was stored in credential-protected dir or vice versa
                    val mirrorFile = findMirroredCustomSoundFile(context, f.name)
                    mirrorFile != null && mirrorFile.exists() && mirrorFile.length() > 44L
                }
                "android.resource" -> {
                    val lastSeg = uri.lastPathSegment ?: return false
                    val resId = lastSeg.toIntOrNull()
                        ?: context.resources.getIdentifier(lastSeg, "raw", context.packageName)
                    if (resId != 0 && uri.authority == context.packageName) {
                        isRawSoundResourceValid(context, resId)
                    } else {
                        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                            fd.length != 0L
                        } ?: false
                    }
                }
                "content" -> {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                        fd.length != 0L
                    } ?: (RingtoneManager.getRingtone(context, uri) != null)
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    fun findMirroredCustomSoundFile(context: Context, fileName: String): File? {
        if (fileName.isBlank()) return null
        return runCatching {
            if (isCredentialStorageUnlocked(context)) {
                val credFile = File(File(context.filesDir, "alarm_sounds"), fileName)
                if (credFile.exists() && credFile.length() > 44L) return credFile
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val dpCtx = context.createDeviceProtectedStorageContext()
                val dpFile = File(File(dpCtx.filesDir, "alarm_sounds"), fileName)
                if (dpFile.exists() && dpFile.length() > 44L) return dpFile
            }
            null
        }.getOrNull()
    }

    /**
     * Copies a custom sound file into both credential-protected and device-protected storage
     * so that it remains accessible immediately after reboot (even during Direct Boot).
     */
    fun mirrorCustomSoundFileToDeviceProtectedStorage(context: Context, sourceFile: File) {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                val dpCtx = context.createDeviceProtectedStorageContext()
                val dpDir = File(dpCtx.filesDir, "alarm_sounds").apply { mkdirs() }
                val dpDest = File(dpDir, sourceFile.name)
                if (!dpDest.exists() || dpDest.length() != sourceFile.length()) {
                    sourceFile.inputStream().use { input ->
                        FileOutputStream(dpDest).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    private fun isCredentialStorageUnlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = context.getSystemService(UserManager::class.java)
            if (um != null && !um.isUserUnlocked) return false
        }
        return true
    }

    private fun getReadablePrefs(context: Context) = run {
        val appCtx = context.applicationContext ?: context
        if (isCredentialStorageUnlocked(appCtx)) {
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appCtx.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } else {
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * Loads the persisted [SelectedSoundConfig] for a [SoundUsageType], or returns its default
     * library sound configuration if none was explicitly saved yet.
     */
    fun getSoundConfigForUsage(context: Context, usage: SoundUsageType): SelectedSoundConfig {
        val prefs = getReadablePrefs(context)
        val savedType = prefs.getString("${usage.prefKey}_type", null)
        val savedUri = prefs.getString("${usage.prefKey}_uri", null)
        val savedTitle = prefs.getString("${usage.prefKey}_title", null)
        val savedVolume = prefs.getInt("${usage.prefKey}_vol", 85).coerceIn(10, 100)

        if (!savedType.isNullOrBlank()) {
            // Verify that if it's a custom/library URI, it is still accessible; otherwise fall back cleanly
            if (savedType == AlarmAudioController.SOUND_CUSTOM_URI || savedType.startsWith(SOUND_PREFIX_LIBRARY)) {
                val libItem = findLibrarySoundById(context, savedType)
                    ?: findLibrarySoundByUri(context, savedUri.orEmpty())
                if (libItem != null) {
                    return SelectedSoundConfig(
                        soundType = "$SOUND_PREFIX_LIBRARY${libItem.id}",
                        customSoundUri = libItem.buildResourceUri(context),
                        customSoundTitle = libItem.titleHe,
                        volumePercent = savedVolume
                    )
                }
                if (!savedUri.isNullOrBlank() && isUriAccessibleAndValid(context, savedUri)) {
                    return SelectedSoundConfig(
                        soundType = savedType,
                        customSoundUri = resolveAccessibleFileOrContentUri(context, savedUri),
                        customSoundTitle = savedTitle.orEmpty(),
                        volumePercent = savedVolume
                    )
                }
                Log.w(TAG, "Saved custom URI for ${usage.name} is no longer accessible ($savedUri); falling back to default.")
            } else {
                return SelectedSoundConfig(
                    soundType = savedType,
                    customSoundUri = savedUri.orEmpty(),
                    customSoundTitle = savedTitle.orEmpty(),
                    volumePercent = savedVolume
                )
            }
        }

        val defaultItem = findLibrarySoundById(context, usage.defaultSoundId)
        return if (defaultItem != null) {
            SelectedSoundConfig(
                soundType = "$SOUND_PREFIX_LIBRARY${defaultItem.id}",
                customSoundUri = defaultItem.buildResourceUri(context),
                customSoundTitle = defaultItem.titleHe,
                volumePercent = 85
            )
        } else {
            SelectedSoundConfig(
                soundType = if (usage.isAlarmStream) {
                    AlarmAudioController.SOUND_SYSTEM_ALARM
                } else {
                    AlarmAudioController.SOUND_SYSTEM_NOTIFICATION
                },
                customSoundUri = "",
                customSoundTitle = "",
                volumePercent = 85
            )
        }
    }

    /**
     * Persists the selected sound configuration for [usage] in both credential-protected and
     * device-protected preferences, and updates the reactive [usageConfigsFlow].
     */
    fun saveSoundConfigForUsage(
        context: Context,
        usage: SoundUsageType,
        soundType: String,
        customSoundUri: String,
        customSoundTitle: String,
        volumePercent: Int = 85
    ): SelectedSoundConfig {
        val appCtx = context.applicationContext ?: context
        val libItem = findLibrarySoundById(appCtx, soundType)
            ?: findLibrarySoundByUri(appCtx, customSoundUri)

        val normalizedType = when {
            libItem != null -> "$SOUND_PREFIX_LIBRARY${libItem.id}"
            else -> soundType
        }
        val normalizedUri = when {
            libItem != null -> libItem.buildResourceUri(appCtx)
            else -> customSoundUri
        }
        val normalizedTitle = when {
            libItem != null && customSoundTitle.isBlank() -> libItem.titleHe
            else -> customSoundTitle
        }
        val clampedVol = volumePercent.coerceIn(10, 100)

        fun writeToPrefs(targetCtx: Context) {
            runCatching {
                targetCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString("${usage.prefKey}_type", normalizedType)
                    .putString("${usage.prefKey}_uri", normalizedUri)
                    .putString("${usage.prefKey}_title", normalizedTitle)
                    .putInt("${usage.prefKey}_vol", clampedVol)
                    .apply()
            }
        }

        if (isCredentialStorageUnlocked(appCtx)) {
            writeToPrefs(appCtx)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            writeToPrefs(appCtx.createDeviceProtectedStorageContext())
        }

        refreshUsageConfigsState(appCtx)
        return getSoundConfigForUsage(appCtx, usage)
    }

    fun refreshUsageConfigsState(context: Context): Map<SoundUsageType, SelectedSoundConfig> {
        val map = SoundUsageType.entries.associateWith { usage ->
            getSoundConfigForUsage(context, usage)
        }
        _usageConfigsFlow.value = map
        return map
    }

    fun resolveAccessibleFileOrContentUri(context: Context, uriString: String): String {
        if (uriString.isBlank()) return ""
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return uriString
        if (parsed.scheme.equals("file", ignoreCase = true)) {
            val path = parsed.path.orEmpty()
            val f = File(path)
            if (f.exists() && f.canRead() && f.length() > 44L) return uriString
            val mirror = findMirroredCustomSoundFile(context, f.name)
            if (mirror != null) return Uri.fromFile(mirror).toString()
        }
        return uriString
    }

    /**
     * Resolves a guaranteed playable [Uri] for a given (`soundType`, `customSoundUri`) pair,
     * falling back to the [fallbackUsage] default sound or system default if a custom file was
     * deleted or its URI is inaccessible.
     */
    fun resolvePlayableUriOrFallback(
        context: Context,
        soundType: String,
        customSoundUri: String,
        fallbackUsage: SoundUsageType = SoundUsageType.ALARM_DEFAULT
    ): Uri? {
        val appCtx = context.applicationContext ?: context

        // 1. Check if soundType or customSoundUri maps to a verified built-in raw library sound
        val libItem = findLibrarySoundById(appCtx, soundType)
            ?: findLibrarySoundByUri(appCtx, customSoundUri)
        if (libItem != null) {
            return Uri.parse(libItem.buildResourceUri(appCtx))
        }

        // 2. Check custom / picked URI if accessible
        if (customSoundUri.isNotBlank() && isUriAccessibleAndValid(appCtx, customSoundUri)) {
            val resolvedStr = resolveAccessibleFileOrContentUri(appCtx, customSoundUri)
            return runCatching { Uri.parse(resolvedStr) }.getOrNull()
        }

        // 3. If soundType is a specific system sound (SYSTEM_ALARM / SYSTEM_RINGTONE / SYSTEM_NOTIFICATION)
        if (soundType == AlarmAudioController.SOUND_SYSTEM_ALARM ||
            soundType == AlarmAudioController.SOUND_SYSTEM_RINGTONE ||
            soundType == AlarmAudioController.SOUND_SYSTEM_NOTIFICATION
        ) {
            val ringtoneType = when (soundType) {
                AlarmAudioController.SOUND_SYSTEM_RINGTONE -> RingtoneManager.TYPE_RINGTONE
                AlarmAudioController.SOUND_SYSTEM_NOTIFICATION -> RingtoneManager.TYPE_NOTIFICATION
                else -> RingtoneManager.TYPE_ALARM
            }
            val sysUri = runCatching {
                RingtoneManager.getActualDefaultRingtoneUri(appCtx, ringtoneType)
                    ?: RingtoneManager.getDefaultUri(ringtoneType)
            }.getOrNull()
            if (sysUri != null) return sysUri
        }

        // 4. Fall back to the category's default verified library sound
        val defaultItem = findLibrarySoundById(appCtx, fallbackUsage.defaultSoundId)
        if (defaultItem != null) {
            return Uri.parse(defaultItem.buildResourceUri(appCtx))
        }

        // 5. Final fallback to Android system default URI
        return runCatching {
            val defaultType = if (fallbackUsage.isAlarmStream) {
                RingtoneManager.TYPE_ALARM
            } else {
                RingtoneManager.TYPE_NOTIFICATION
            }
            RingtoneManager.getDefaultUri(defaultType)
        }.getOrNull()
    }

    /**
     * Returns a user-friendly localized display label for any (`soundType`, `customSoundUri`, `customTitle`).
     */
    fun formatSoundDisplayName(
        context: Context,
        soundType: String,
        customSoundUri: String,
        customTitle: String,
        isHebrew: Boolean
    ): String {
        val libItem = findLibrarySoundById(context, soundType)
            ?: findLibrarySoundByUri(context, customSoundUri)
        if (libItem != null) {
            return if (isHebrew) libItem.titleHe else libItem.titleEn
        }
        if (customTitle.isNotBlank()) {
            if (customSoundUri.isBlank() || isUriAccessibleAndValid(context, customSoundUri)) {
                return customTitle
            }
        }
        return AlarmAudioController.cleanSoundDisplayName(
            soundType = soundType,
            customTitle = if (isUriAccessibleAndValid(context, customSoundUri)) customTitle else "",
            isHebrew = isHebrew
        )
    }

    /**
     * Plays a short notification/reminder alert sound on `USAGE_NOTIFICATION` when a custom
     * app-private `file://` URI is used (which SystemUI cannot read directly from a NotificationChannel)
     * or when deterministic immediate playback is needed.
     */
    fun playNotificationSoundOneShot(
        context: Context,
        uri: Uri,
        volumePercent: Int = 85
    ): Boolean {
        val appCtx = context.applicationContext ?: context
        val scalar = (volumePercent.coerceIn(10, 100) / 100f).coerceIn(0.1f, 1.0f)
        return runCatching {
            synchronized(oneShotLock) {
                activeOneShotPlayer?.let { prev ->
                    runCatching {
                        if (prev.isPlaying) prev.stop()
                        prev.reset()
                        prev.release()
                    }
                }
                activeOneShotPlayer = null
            }
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(appCtx, uri)
            mp.isLooping = false
            mp.setVolume(scalar, scalar)
            mp.setOnCompletionListener { player ->
                synchronized(oneShotLock) {
                    if (activeOneShotPlayer === player) {
                        activeOneShotPlayer = null
                    }
                }
                runCatching {
                    player.reset()
                    player.release()
                }
            }
            mp.setOnErrorListener { player, _, _ ->
                synchronized(oneShotLock) {
                    if (activeOneShotPlayer === player) {
                        activeOneShotPlayer = null
                    }
                }
                runCatching {
                    player.reset()
                    player.release()
                }
                true
            }
            mp.prepare()
            mp.start()
            synchronized(oneShotLock) {
                activeOneShotPlayer = mp
            }
            true
        }.getOrDefault(false)
    }

    fun saveSoundConfigForUsage(
        context: Context,
        usageType: SoundUsageType,
        config: SelectedSoundConfig
    ): SelectedSoundConfig {
        val effectiveSoundType = config.soundId.ifBlank { config.soundType }
        val effectiveUri = config.customUri.ifBlank { config.customSoundUri }
        val effectiveTitle = config.customTitle.ifBlank { config.customSoundTitle }
        return saveSoundConfigForUsage(
            context = context,
            usage = usageType,
            soundType = effectiveSoundType,
            customSoundUri = effectiveUri,
            customSoundTitle = effectiveTitle,
            volumePercent = config.volumePercent
        )
    }

    fun resolvePlayableUriOrFallback(
        context: Context,
        usageType: SoundUsageType
    ): Uri {
        val cfg = getSoundConfigForUsage(context, usageType)
        return resolvePlayableUriOrFallback(
            context = context,
            soundType = cfg.soundId.ifBlank { cfg.soundType },
            customSoundUri = cfg.customUri.ifBlank { cfg.customSoundUri },
            fallbackUsage = usageType
        ) ?: Uri.parse("android.resource://${context.packageName}/${R.raw.sound_soft_reminder_dew}")
    }

    fun formatSelectedSoundDisplayName(
        context: Context,
        usageType: SoundUsageType,
        isHebrew: Boolean
    ): String {
        val cfg = getSoundConfigForUsage(context, usageType)
        return formatSoundDisplayName(
            context = context,
            soundType = cfg.soundId.ifBlank { cfg.soundType },
            customSoundUri = cfg.customUri.ifBlank { cfg.customSoundUri },
            customTitle = cfg.customTitle.ifBlank { cfg.customSoundTitle },
            isHebrew = isHebrew
        )
    }

    fun clearAllForTesting(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            }
        }
        _usageConfigsFlow.value = emptyMap()
    }

    fun clearAllSoundPrefsForTesting(context: Context) = clearAllForTesting(context)
}
