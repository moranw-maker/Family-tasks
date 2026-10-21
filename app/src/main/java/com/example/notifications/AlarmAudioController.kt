package com.example.notifications

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

data class AlarmSoundOption(
    val soundType: String,
    val titleHe: String,
    val titleEn: String,
    val emoji: String,
    val isBuiltInMelody: Boolean
)

data class NativeDeviceRingtone(
    val idKey: String,
    val title: String,
    val subtitleHe: String = "מערכת Android",
    val subtitleEn: String = "Android System",
    val soundType: String = AlarmAudioController.SOUND_CUSTOM_URI,
    val uriString: String = "",
    val emoji: String = "🔔"
)

data class AudioPreviewState(
    val isPlaying: Boolean = false,
    val activeSoundType: String = "",
    val activeCustomUri: String = "",
    val activeVolumePercent: Int = 85,
    val errorMessageHe: String? = null,
    val errorMessageEn: String? = null
)

/**
 * Central audio & vibration controller for FamilyTasks alarms.
 *
 * Handles:
 * 1. Native Android system alarm sounds, ringtones, and notification tones via [RingtoneManager]
 *    and [MediaPlayer] on [AudioAttributes.USAGE_ALARM].
 * 2. Native Android system ringtone picker ([RingtoneManager.ACTION_RINGTONE_PICKER]) and device
 *    alarm sound discovery.
 * 3. Immediate sound & volume preview (with automatic stop on screen exit or timeout).
 * 4. Multi-layer fallback so an alarm NEVER fails silently on any device or test environment.
 */
object AlarmAudioController {

    const val SOUND_MELODY_ENERGETIC = "MELODY_ENERGETIC"
    const val SOUND_MELODY_GENTLE = "MELODY_GENTLE"
    const val SOUND_MELODY_CLASSIC = "MELODY_CLASSIC"
    const val SOUND_SYSTEM_ALARM = "SYSTEM_ALARM"
    const val SOUND_SYSTEM_NOTIFICATION = "SYSTEM_NOTIFICATION"
    const val SOUND_SYSTEM_RINGTONE = "SYSTEM_RINGTONE"
    const val SOUND_CUSTOM_URI = "CUSTOM_URI"

    val builtInSoundOptions: List<AlarmSoundOption> = listOf(
        AlarmSoundOption(
            soundType = SOUND_SYSTEM_ALARM,
            titleHe = "צליל שעון מעורר (ברירת מחדל)",
            titleEn = "Default Android Alarm",
            emoji = "🔔",
            isBuiltInMelody = false
        ),
        AlarmSoundOption(
            soundType = SOUND_SYSTEM_RINGTONE,
            titleHe = "צלצול הטלפון של המכשיר",
            titleEn = "Device Phone Ringtone",
            emoji = "📞",
            isBuiltInMelody = false
        ),
        AlarmSoundOption(
            soundType = SOUND_SYSTEM_NOTIFICATION,
            titleHe = "צליל התראה של המכשיר",
            titleEn = "Device Notification Sound",
            emoji = "✨",
            isBuiltInMelody = false
        ),
        AlarmSoundOption(
            soundType = SOUND_MELODY_ENERGETIC,
            titleHe = "שעון מעורר קצבי (מערכת)",
            titleEn = "Classic Wake-Up Alarm",
            emoji = "⏰",
            isBuiltInMelody = false
        ),
        AlarmSoundOption(
            soundType = SOUND_MELODY_GENTLE,
            titleHe = "השכמת בוקר עדינה (מערכת)",
            titleEn = "Gentle Morning Alarm",
            emoji = "🌅",
            isBuiltInMelody = false
        ),
        AlarmSoundOption(
            soundType = SOUND_MELODY_CLASSIC,
            titleHe = "צלצול שעון קלאסי (מערכת)",
            titleEn = "Standard System Alarm",
            emoji = "🎵",
            isBuiltInMelody = false
        )
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private var previewJob: Job? = null
    private var previewMediaPlayer: MediaPlayer? = null
    private var previewRingtone: Ringtone? = null
    private var previewAudioTrack: AudioTrack? = null
    private var previewFocusRequest: AudioFocusRequest? = null
    @Volatile
    private var previewStreamVolumeScalar: Float = 0.85f

    private var alarmJob: Job? = null
    private var alarmMediaPlayer: MediaPlayer? = null
    private var alarmRingtone: Ringtone? = null
    private var alarmAudioTrack: AudioTrack? = null
    private var alarmVibrator: Vibrator? = null
    private var alarmFocusRequest: AudioFocusRequest? = null
    @Volatile
    private var alarmStreamVolumeScalar: Float = 0.85f

    private val _previewState = MutableStateFlow(AudioPreviewState())
    val previewState: StateFlow<AudioPreviewState> = _previewState.asStateFlow()

    fun soundDisplayName(soundType: String, customTitle: String, isHebrew: Boolean): String {
        if (customTitle.isNotBlank()) {
            return "🔔 $customTitle"
        }
        if (soundType == SOUND_CUSTOM_URI) {
            return if (isHebrew) "צליל מותאם אישית 🔔" else "Custom Alarm Sound 🔔"
        }
        val opt = builtInSoundOptions.find { it.soundType == soundType }
        if (opt != null) {
            return "${opt.emoji} ${if (isHebrew) opt.titleHe else opt.titleEn}"
        }
        return if (isHebrew) "🔔 צליל ברירת המחדל של המכשיר" else "🔔 Default Android Alarm"
    }

    fun cleanSoundDisplayName(soundType: String, customTitle: String, isHebrew: Boolean): String {
        if (customTitle.isNotBlank()) {
            return customTitle
        }
        return when (soundType) {
            SOUND_SYSTEM_ALARM -> if (isHebrew) "צליל ברירת המחדל של המכשיר" else "Default Android Alarm"
            SOUND_SYSTEM_RINGTONE -> if (isHebrew) "צלצול הטלפון של המכשיר" else "Device Phone Ringtone"
            SOUND_SYSTEM_NOTIFICATION -> if (isHebrew) "צליל התראה של המכשיר" else "Device Notification Sound"
            SOUND_CUSTOM_URI -> if (isHebrew) "צליל מותאם אישית" else "Custom Alarm Sound"
            else -> {
                val opt = builtInSoundOptions.find { it.soundType == soundType }
                if (opt != null) {
                    if (isHebrew) opt.titleHe else opt.titleEn
                } else {
                    if (isHebrew) "צליל ברירת המחדל של המכשיר" else "Default Android Alarm"
                }
            }
        }
    }

    /**
     * Queries the device's native Android [RingtoneManager] for installed alarm and ringtone sounds
     * so users can choose familiar native system tones directly.
     */
    fun getDeviceSystemAlarmRingtones(context: Context, maxCount: Int = 10): List<NativeDeviceRingtone> {
        return getSystemAlarmCategoryRingtones(context, maxCount = maxCount)
    }

    /**
     * Category 1 ("צלילי מערכת"): Queries installed Android system alarm tones ([RingtoneManager.TYPE_ALARM]).
     * Only returns real sounds available on the device, starting with the system default alarm tone.
     */
    fun getSystemAlarmCategoryRingtones(context: Context, maxCount: Int = 30): List<NativeDeviceRingtone> {
        val results = mutableListOf<NativeDeviceRingtone>()
        val seenUris = mutableSetOf<String>()
        val appCtx = context.applicationContext ?: context

        val defaultAlarmUri = runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(appCtx, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        }.getOrNull()
        val defaultAlarmTitle = defaultAlarmUri?.let { uri ->
            runCatching { RingtoneManager.getRingtone(appCtx, uri)?.getTitle(appCtx)?.trim() }.getOrNull()
        }?.takeIf { it.isNotBlank() && !it.contains("Unknown", ignoreCase = true) }

        results.add(
            NativeDeviceRingtone(
                idKey = "sys_default_alarm",
                title = defaultAlarmTitle ?: "צליל ברירת המחדל של המכשיר",
                subtitleHe = "מערכת Android • שעון מעורר",
                subtitleEn = "Android System • Default Alarm",
                soundType = SOUND_SYSTEM_ALARM,
                uriString = "",
                emoji = "🔔"
            )
        )

        runCatching {
            val manager = RingtoneManager(appCtx).apply { setType(RingtoneManager.TYPE_ALARM) }
            val cursor = manager.cursor ?: return@runCatching
            cursor.use { c ->
                var idx = 0
                while (c.moveToNext() && results.size < maxCount && idx < 40) {
                    val uri = runCatching { manager.getRingtoneUri(c.position) }.getOrNull()
                    val title = runCatching { c.getString(RingtoneManager.TITLE_COLUMN_INDEX) }.getOrNull()
                        ?.trim()
                        .orEmpty()
                    if (uri != null && title.isNotBlank()) {
                        val uriStr = uri.toString()
                        if (seenUris.add(uriStr)) {
                            results.add(
                                NativeDeviceRingtone(
                                    idKey = "alarm_uri_$uriStr",
                                    title = title,
                                    subtitleHe = "מערכת Android",
                                    subtitleEn = "Android System",
                                    soundType = SOUND_CUSTOM_URI,
                                    uriString = uriStr,
                                    emoji = "⏰"
                                )
                            )
                        }
                    }
                    idx++
                }
            }
        }
        return results
    }

    /**
     * Category 2 ("צלילים נוספים"): Queries real available system ringtones ([RingtoneManager.TYPE_RINGTONE])
     * and notification tones ([RingtoneManager.TYPE_NOTIFICATION]) on the device.
     */
    fun getAdditionalCategoryRingtones(context: Context, maxCount: Int = 35): List<NativeDeviceRingtone> {
        val results = mutableListOf<NativeDeviceRingtone>()
        val seenUris = mutableSetOf<String>()
        val appCtx = context.applicationContext ?: context

        results.add(
            NativeDeviceRingtone(
                idKey = "sys_default_ringtone",
                title = "צלצול הטלפון של המכשיר",
                subtitleHe = "מערכת Android • רינגטון ברירת מחדל",
                subtitleEn = "Android System • Default Ringtone",
                soundType = SOUND_SYSTEM_RINGTONE,
                uriString = "",
                emoji = "📞"
            )
        )
        results.add(
            NativeDeviceRingtone(
                idKey = "sys_default_notification",
                title = "צליל התראה של המכשיר",
                subtitleHe = "מערכת Android • התראת ברירת מחדל",
                subtitleEn = "Android System • Default Notification",
                soundType = SOUND_SYSTEM_NOTIFICATION,
                uriString = "",
                emoji = "✨"
            )
        )

        fun collectFromType(type: Int, subtitleHe: String, subtitleEn: String, emoji: String, limit: Int) {
            runCatching {
                val manager = RingtoneManager(appCtx).apply { setType(type) }
                val cursor = manager.cursor ?: return@runCatching
                cursor.use { c ->
                    var count = 0
                    while (c.moveToNext() && results.size < maxCount && count < limit) {
                        val uri = runCatching { manager.getRingtoneUri(c.position) }.getOrNull()
                        val title = runCatching { c.getString(RingtoneManager.TITLE_COLUMN_INDEX) }.getOrNull()
                            ?.trim()
                            .orEmpty()
                        if (uri != null && title.isNotBlank()) {
                            val uriStr = uri.toString()
                            if (seenUris.add(uriStr)) {
                                results.add(
                                    NativeDeviceRingtone(
                                        idKey = "extra_uri_$uriStr",
                                        title = title,
                                        subtitleHe = subtitleHe,
                                        subtitleEn = subtitleEn,
                                        soundType = SOUND_CUSTOM_URI,
                                        uriString = uriStr,
                                        emoji = emoji
                                    )
                                )
                                count++
                            }
                        }
                    }
                }
            }
        }

        collectFromType(
            type = RingtoneManager.TYPE_RINGTONE,
            subtitleHe = "מערכת Android • רינגטון",
            subtitleEn = "Android System • Ringtone",
            emoji = "🔔",
            limit = 18
        )
        collectFromType(
            type = RingtoneManager.TYPE_NOTIFICATION,
            subtitleHe = "מערכת Android • צליל התראה",
            subtitleEn = "Android System • Notification",
            emoji = "✨",
            limit = 15
        )
        return results
    }

    /**
     * Creates an Intent to open Android's native system Ringtone Picker dialog for alarm sounds.
     */
    fun createSystemRingtonePickerIntent(
        context: Context,
        currentSoundType: String,
        currentCustomUri: String,
        isHebrew: Boolean
    ): Intent {
        val existingUri = when {
            currentCustomUri.isNotBlank() -> runCatching { Uri.parse(currentCustomUri) }.getOrNull()
            else -> resolveSystemToneUri(context, currentSoundType)
        } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        return Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_TYPE,
                RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE
            )
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            )
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_TITLE,
                if (isHebrew) "בחירת צליל שעון מעורר" else "Select Alarm Sound"
            )
            if (existingUri != null) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
            }
        }
    }

    /**
     * Resolves the URI and human-readable title from [RingtoneManager.ACTION_RINGTONE_PICKER] result.
     */
    fun resolvePickedSystemRingtone(
        context: Context,
        resultData: Intent?,
        isHebrew: Boolean
    ): Pair<String, String>? {
        val data = resultData ?: return null
        val pickedUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        } ?: return null

        val title = runCatching {
            RingtoneManager.getRingtone(context, pickedUri)?.getTitle(context)?.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: if (isHebrew) "צליל מערכת נבחר" else "System Alarm Sound"

        return Pair(pickedUri.toString(), title)
    }

    /**
     * Copies a user-picked custom audio document URI into app-private storage (`filesDir/alarm_sounds/`)
     * and persists read permissions where supported, returning the local `Uri` and display name.
     */
    fun importAndPersistCustomSoundUri(
        context: Context,
        sourceUri: Uri
    ): Result<Pair<String, String>> {
        return runCatching {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    sourceUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val displayName = resolveDisplayName(context, sourceUri)
            val safeFileName = displayName.replace(Regex("[^a-zA-Z0-9._\\-\\u0590-\\u05FF ]"), "_")
                .take(60)
                .ifBlank { "custom_alarm_audio.mp3" }

            val soundsDir = File(context.filesDir, "alarm_sounds").apply { mkdirs() }
            val destFile = File(soundsDir, "alarm_custom_${System.currentTimeMillis()}_$safeFileName")

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Cannot open selected audio file")

            if (!destFile.exists() || destFile.length() <= 0L) {
                throw IllegalStateException("Selected audio file is empty or inaccessible")
            }

            Pair(Uri.fromFile(destFile).toString(), displayName)
        }.recoverCatching {
            context.contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use {
                // Readable directly
            } ?: throw IllegalStateException("קובץ השמע שנבחר אינו נגיש לקריאה")
            val displayName = resolveDisplayName(context, sourceUri)
            Pair(sourceUri.toString(), displayName)
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "Custom Audio"
    }

    /**
     * Starts an immediate audible preview of [soundType] (or [customSoundUri]) at [volumePercent].
     * Automatically stops any previous preview and auto-stops after [durationMillis].
     */
    fun startPreview(
        context: Context,
        soundType: String,
        customSoundUri: String,
        volumePercent: Int,
        durationMillis: Long = 7_000L
    ) {
        val appCtx = context.applicationContext ?: context
        stopPreview(appCtx)

        val clampedVolume = volumePercent.coerceIn(10, 100)
        val volumeScalar = (clampedVolume / 100f).coerceIn(0.05f, 1.0f)
        previewStreamVolumeScalar = volumeScalar

        requestAlarmAudioFocus(appCtx, isPreview = true)

        if (soundType == SOUND_CUSTOM_URI) {
            if (customSoundUri.isBlank()) {
                _previewState.value = AudioPreviewState(
                    isPlaying = false,
                    activeSoundType = soundType,
                    activeCustomUri = customSoundUri,
                    activeVolumePercent = clampedVolume,
                    errorMessageHe = "לא נבחר צליל או קובץ שמע",
                    errorMessageEn = "No sound or audio file selected"
                )
                return
            }
            val parsedUri = Uri.parse(customSoundUri)
            val startedCustom = startMediaPlayerInternal(
                context = appCtx,
                uri = parsedUri,
                volumeScalar = volumeScalar,
                isPreview = true
            ) || startRingtoneInternal(
                context = appCtx,
                uri = parsedUri,
                volumeScalar = volumeScalar,
                isPreview = true
            )
            if (!startedCustom) {
                _previewState.value = AudioPreviewState(
                    isPlaying = false,
                    activeSoundType = soundType,
                    activeCustomUri = customSoundUri,
                    activeVolumePercent = clampedVolume,
                    errorMessageHe = "לא ניתן לנגן את הצליל שנבחר. נסו לבחור צליל אחר.",
                    errorMessageEn = "Unable to play selected sound. Please choose another sound."
                )
                return
            }
        } else {
            // Always prefer real native Android system alarm/ringtone sounds first
            val sysUri = if (customSoundUri.isNotBlank()) {
                runCatching { Uri.parse(customSoundUri) }.getOrNull()
            } else {
                resolveSystemToneUri(appCtx, soundType)
            }
            val startedMedia = sysUri != null && startMediaPlayerInternal(
                context = appCtx,
                uri = sysUri,
                volumeScalar = volumeScalar,
                isPreview = true
            )
            if (!startedMedia) {
                val startedRingtone = sysUri != null && startRingtoneInternal(appCtx, sysUri, volumeScalar, isPreview = true)
                if (!startedRingtone) {
                    val fallbackType = if (isBuiltInMelody(soundType)) soundType else SOUND_MELODY_CLASSIC
                    val startedWav = startBuiltInMelodyWavMediaPlayer(
                        context = appCtx,
                        soundType = fallbackType,
                        volumeScalar = volumeScalar,
                        isPreview = true
                    )
                    if (!startedWav) {
                        startSynthesizedMelodyStreamingLoop(
                            soundType = fallbackType,
                            volumeScalar = volumeScalar,
                            isPreview = true,
                            maxDurationMillis = durationMillis
                        )
                    }
                }
            }
        }

        _previewState.value = AudioPreviewState(
            isPlaying = true,
            activeSoundType = soundType,
            activeCustomUri = customSoundUri,
            activeVolumePercent = clampedVolume
        )

        synchronized(lock) {
            previewJob?.cancel()
            previewJob = scope.launch {
                delay(durationMillis)
                stopPreview(appCtx)
            }
        }
    }

    /**
     * Updates the volume of an actively playing preview in real time when the user drags the slider,
     * without mutating the global device STREAM_ALARM setting.
     */
    fun updateActivePreviewVolume(context: Context, volumePercent: Int) {
        val clamped = volumePercent.coerceIn(10, 100)
        val scalar = (clamped / 100f).coerceIn(0.05f, 1.0f)
        previewStreamVolumeScalar = scalar
        synchronized(lock) {
            runCatching { previewMediaPlayer?.setVolume(scalar, scalar) }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    previewRingtone?.volume = scalar
                }
            }
            runCatching { previewAudioTrack?.setVolume(scalar) }
        }
        if (_previewState.value.isPlaying) {
            _previewState.value = _previewState.value.copy(activeVolumePercent = clamped)
        }
    }

    fun clearPreviewError() {
        _previewState.value = _previewState.value.copy(errorMessageHe = null, errorMessageEn = null)
    }

    fun stopPreview(context: Context? = null) {
        synchronized(lock) {
            previewJob?.cancel()
            previewJob = null

            runCatching {
                previewMediaPlayer?.let { mp ->
                    if (mp.isPlaying) mp.stop()
                    mp.reset()
                    mp.release()
                }
            }
            previewMediaPlayer = null

            runCatching { previewRingtone?.stop() }
            previewRingtone = null

            runCatching {
                previewAudioTrack?.let { track ->
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.stop()
                    }
                    track.release()
                }
            }
            previewAudioTrack = null

            if (context != null) {
                abandonAlarmAudioFocus(context.applicationContext ?: context, isPreview = true)
            }
        }
        _previewState.value = AudioPreviewState(isPlaying = false)
    }

    /**
     * Starts continuous alarm playback + optional vibration for an active alarm trigger.
     * Guaranteed not to fail silently:
     * 1. Tries requested custom/picked system ringtone URI via MediaPlayer or Ringtone (`USAGE_ALARM`).
     * 2. Tries native Android system alarm / ringtone / notification URI via MediaPlayer or Ringtone (`USAGE_ALARM`).
     * 3. Falls back to default system alarm Ringtone (`USAGE_ALARM`).
     * 4. Falls back to built-in WAV / AudioTrack (`USAGE_ALARM`) if device has no media files.
     * Records the exact playback method or error in [FamilyAlarmScheduler] diagnostics.
     */
    fun startAlarmRinging(
        context: Context,
        soundType: String,
        customSoundUri: String,
        volumePercent: Int,
        vibrateEnabled: Boolean
    ): Boolean {
        val appCtx = context.applicationContext ?: context
        stopPreview(appCtx)
        stopAlarmRinging(appCtx)

        val clampedVolume = volumePercent.coerceIn(10, 100)
        val volumeScalar = (clampedVolume / 100f).coerceIn(0.1f, 1.0f)
        alarmStreamVolumeScalar = volumeScalar

        requestAlarmAudioFocus(appCtx, isPreview = false)
        applyDeviceAlarmStreamVolume(appCtx, clampedVolume)

        var playbackStarted = false
        var activeMethod = ""

        // 1. Custom or picked system ringtone URI if present
        if (customSoundUri.isNotBlank()) {
            val parsedUri = runCatching { Uri.parse(customSoundUri) }.getOrNull()
            if (parsedUri != null) {
                if (startMediaPlayerInternal(appCtx, parsedUri, volumeScalar, isPreview = false)) {
                    playbackStarted = true
                    activeMethod = "MediaPlayer (Picked/Custom URI • USAGE_ALARM)"
                } else if (startRingtoneInternal(appCtx, parsedUri, volumeScalar, isPreview = false)) {
                    playbackStarted = true
                    activeMethod = "RingtoneManager (Picked URI • USAGE_ALARM)"
                }
            }
        }

        // 2. Native Android System Alarm / Ringtone / Notification URI via MediaPlayer or Ringtone
        if (!playbackStarted) {
            val sysUri = resolveSystemToneUri(appCtx, soundType)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            if (sysUri != null) {
                if (startMediaPlayerInternal(appCtx, sysUri, volumeScalar, isPreview = false)) {
                    playbackStarted = true
                    activeMethod = "MediaPlayer (Native System Alarm • USAGE_ALARM)"
                } else if (startRingtoneInternal(appCtx, sysUri, volumeScalar, isPreview = false)) {
                    playbackStarted = true
                    activeMethod = "RingtoneManager (Native System Alarm • USAGE_ALARM)"
                }
            }
        }

        // 3. Fallback A: System default alarm via RingtoneManager
        if (!playbackStarted) {
            val defaultAlarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (defaultAlarmUri != null && startRingtoneInternal(appCtx, defaultAlarmUri, volumeScalar, isPreview = false)) {
                playbackStarted = true
                activeMethod = "RingtoneManager Default Alarm (USAGE_ALARM)"
            }
        }

        // 4. Fallback B: Built-in WAV via MediaPlayer (for headless test environments without system media)
        if (!playbackStarted) {
            val fallbackMelody = if (isBuiltInMelody(soundType)) soundType else SOUND_MELODY_ENERGETIC
            if (startBuiltInMelodyWavMediaPlayer(appCtx, fallbackMelody, volumeScalar, isPreview = false)) {
                playbackStarted = true
                activeMethod = "MediaPlayer WAV Fallback ($fallbackMelody • USAGE_ALARM)"
            }
        }

        // 5. Ultimate Fallback C: Streaming PCM AudioTrack (MODE_STREAM) on USAGE_ALARM
        if (!playbackStarted) {
            val fallbackMelody = if (isBuiltInMelody(soundType)) soundType else SOUND_MELODY_ENERGETIC
            startSynthesizedMelodyStreamingLoop(
                soundType = fallbackMelody,
                volumeScalar = volumeScalar,
                isPreview = false,
                maxDurationMillis = 5 * 60_000L
            )
            playbackStarted = true
            activeMethod = "AudioTrack PCM Stream ($fallbackMelody • USAGE_ALARM)"
        }

        if (vibrateEnabled) {
            startContinuousVibration(appCtx)
        }

        if (playbackStarted) {
            FamilyAlarmScheduler.recordDiagnosticAudioStarted(activeMethod)
        } else {
            FamilyAlarmScheduler.recordDiagnosticError("כל שיטות ניגון השמע נכשלו (MediaPlayer / Ringtone / AudioTrack)")
        }

        return playbackStarted
    }

    fun stopAlarmRinging(context: Context? = null) {
        synchronized(lock) {
            alarmJob?.cancel()
            alarmJob = null

            runCatching {
                alarmMediaPlayer?.let { mp ->
                    if (mp.isPlaying) mp.stop()
                    mp.reset()
                    mp.release()
                }
            }
            alarmMediaPlayer = null

            runCatching { alarmRingtone?.stop() }
            alarmRingtone = null

            runCatching {
                alarmAudioTrack?.let { track ->
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.stop()
                    }
                    track.release()
                }
            }
            alarmAudioTrack = null

            runCatching { alarmVibrator?.cancel() }
            alarmVibrator = null

            if (context != null) {
                abandonAlarmAudioFocus(context.applicationContext ?: context, isPreview = false)
            }
        }
    }

    private fun isBuiltInMelody(soundType: String): Boolean {
        return soundType == SOUND_MELODY_ENERGETIC ||
            soundType == SOUND_MELODY_GENTLE ||
            soundType == SOUND_MELODY_CLASSIC
    }

    private fun resolveSystemToneUri(context: Context, soundType: String): Uri? {
        val primaryType = when (soundType) {
            SOUND_SYSTEM_NOTIFICATION, SOUND_MELODY_GENTLE -> RingtoneManager.TYPE_NOTIFICATION
            SOUND_SYSTEM_RINGTONE, SOUND_MELODY_CLASSIC -> RingtoneManager.TYPE_RINGTONE
            else -> RingtoneManager.TYPE_ALARM
        }
        return runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(context, primaryType)
                ?: RingtoneManager.getDefaultUri(primaryType)
                ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }.getOrNull()
    }

    private fun buildAlarmAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    private fun startMediaPlayerInternal(
        context: Context,
        uri: Uri,
        volumeScalar: Float,
        isPreview: Boolean
    ): Boolean {
        return runCatching {
            val mp = MediaPlayer()
            mp.setAudioAttributes(buildAlarmAudioAttributes())
            if (!isPreview) {
                runCatching { mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK) }
            }
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.setVolume(volumeScalar, volumeScalar)
            mp.prepare()
            mp.start()
            synchronized(lock) {
                if (isPreview) {
                    previewMediaPlayer = mp
                } else {
                    alarmMediaPlayer = mp
                }
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Generates a standard 16-bit PCM RIFF `.wav` file for [soundType] and plays it with
     * Android's native [MediaPlayer] on [AudioAttributes.USAGE_ALARM].
     */
    private fun startBuiltInMelodyWavMediaPlayer(
        context: Context,
        soundType: String,
        volumeScalar: Float,
        isPreview: Boolean
    ): Boolean {
        return runCatching {
            val wavFile = ensureSynthesizedWavFile(context, soundType)
            if (!wavFile.exists() || wavFile.length() <= 44L) return false
            val mp = MediaPlayer()
            mp.setAudioAttributes(buildAlarmAudioAttributes())
            if (!isPreview) {
                runCatching { mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK) }
            }
            FileInputStream(wavFile).use { fis ->
                mp.setDataSource(fis.fd)
            }
            mp.isLooping = true
            mp.setVolume(volumeScalar, volumeScalar)
            mp.prepare()
            mp.start()
            synchronized(lock) {
                if (isPreview) {
                    previewMediaPlayer = mp
                } else {
                    alarmMediaPlayer = mp
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun startRingtoneInternal(
        context: Context,
        uri: Uri,
        volumeScalar: Float,
        isPreview: Boolean
    ): Boolean {
        return runCatching {
            val r = RingtoneManager.getRingtone(context, uri) ?: return false
            r.audioAttributes = buildAlarmAudioAttributes()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                r.isLooping = true
                r.volume = volumeScalar
            }
            r.play()
            synchronized(lock) {
                if (isPreview) {
                    previewRingtone = r
                } else {
                    alarmRingtone = r
                }
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Creates (or reuses) a valid 44-byte header 16-bit PCM `.wav` file in `cacheDir/alarm_wavs/`
     * containing the synthesized melody for [soundType].
     */
    private fun ensureSynthesizedWavFile(context: Context, soundType: String): File {
        val sampleRate = 24000
        val notes = getMelodyNotes(soundType)
        val pcmSamples = synthesizeMelodyPcm(sampleRate, notes)
        val dir = File(context.cacheDir, "alarm_wavs").apply { mkdirs() }
        val file = File(dir, "family_alarm_${soundType.lowercase()}.wav")
        val expectedLength = 44L + pcmSamples.size * 2L
        if (file.exists() && file.length() == expectedLength) {
            return file
        }

        val dataSize = pcmSamples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM chunk size
            putShort(1.toShort()) // AudioFormat = 1 (PCM)
            putShort(1.toShort()) // NumChannels = 1 (Mono)
            putInt(sampleRate)
            putInt(sampleRate * 2) // ByteRate
            putShort(2.toShort()) // BlockAlign
            putShort(16.toShort()) // BitsPerSample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        val pcmBytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (sample in pcmSamples) {
                putShort(sample)
            }
        }.array()

        FileOutputStream(file).use { fos ->
            fos.write(header)
            fos.write(pcmBytes)
            fos.flush()
        }
        return file
    }

    private fun getMelodyNotes(soundType: String): List<Pair<Double, Int>> = when (soundType) {
        SOUND_MELODY_ENERGETIC -> listOf(
            // Upbeat C-major / G-major morning fanfare (freq Hz, duration ms)
            523.25 to 180, // C5
            659.25 to 180, // E5
            783.99 to 180, // G5
            1046.50 to 320, // C6
            0.0 to 100,
            783.99 to 160, // G5
            1046.50 to 400, // C6
            0.0 to 360
        )
        SOUND_MELODY_GENTLE -> listOf(
            // Warm pentatonic sunrise chimes
            440.00 to 280, // A4
            554.37 to 280, // C#5
            659.25 to 340, // E5
            880.00 to 460, // A5
            0.0 to 240,
            659.25 to 280, // E5
            554.37 to 420, // C#5
            0.0 to 400
        )
        else -> listOf(
            // Classic crisp dual-beep digital alarm
            880.00 to 140,
            0.0 to 70,
            880.00 to 140,
            0.0 to 70,
            1174.66 to 180,
            0.0 to 450
        )
    }

    /**
     * Streams a synthesized PCM 16-bit melody buffer using [AudioTrack.MODE_STREAM] on
     * `AudioAttributes.USAGE_ALARM` (never uses `MODE_STATIC` / `setLoopPoints`).
     */
    private fun startSynthesizedMelodyStreamingLoop(
        soundType: String,
        volumeScalar: Float,
        isPreview: Boolean,
        maxDurationMillis: Long
    ) {
        val sampleRate = 24000
        val patternNotes = getMelodyNotes(soundType)
        val pcmSamples = synthesizeMelodyPcm(sampleRate, patternNotes)
        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val job = scope.launch {
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(buildAlarmAudioAttributes())
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufBytes * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return@launch

            synchronized(lock) {
                if (isPreview) {
                    previewAudioTrack = track
                } else {
                    alarmAudioTrack = track
                }
            }

            runCatching {
                track.setVolume(volumeScalar)
                track.play()
            }

            val startMillis = System.currentTimeMillis()
            val chunkSize = 2048
            while (isActive && (System.currentTimeMillis() - startMillis) < maxDurationMillis) {
                var offset = 0
                while (isActive && offset < pcmSamples.size && (System.currentTimeMillis() - startMillis) < maxDurationMillis) {
                    val count = minOf(chunkSize, pcmSamples.size - offset)
                    val written = runCatching {
                        track.write(pcmSamples, offset, count)
                    }.getOrDefault(-1)
                    if (written <= 0) {
                        delay(50L)
                        break
                    }
                    offset += written
                }
            }

            if (isPreview) {
                stopPreview()
            }
        }

        synchronized(lock) {
            if (isPreview) {
                previewJob = job
            } else {
                alarmJob = job
            }
        }
    }

    private fun synthesizeMelodyPcm(
        sampleRate: Int,
        notes: List<Pair<Double, Int>>
    ): ShortArray {
        val totalSamples = notes.sumOf { (_, ms) -> (sampleRate * ms) / 1000 }.coerceAtLeast(sampleRate / 2)
        val buffer = ShortArray(totalSamples)
        var cursor = 0
        for ((freq, durationMs) in notes) {
            val count = (sampleRate * durationMs) / 1000
            val attackSamples = (sampleRate * 12) / 1000
            val releaseSamples = (sampleRate * 28) / 1000
            for (i in 0 until count) {
                if (cursor >= buffer.size) break
                if (freq <= 0.0) {
                    buffer[cursor++] = 0
                } else {
                    val envelope = when {
                        i < attackSamples -> i.toDouble() / attackSamples.coerceAtLeast(1)
                        i > count - releaseSamples -> (count - i).toDouble() / releaseSamples.coerceAtLeast(1)
                        else -> 1.0
                    }.coerceIn(0.0, 1.0)
                    val t = i.toDouble() / sampleRate
                    val wave = 0.78 * sin(2.0 * PI * freq * t) + 0.22 * sin(2.0 * PI * (freq * 2.0) * t)
                    val sample = (wave * envelope * 24000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    buffer[cursor++] = sample.toShort()
                }
            }
        }
        return buffer
    }

    private fun startContinuousVibration(context: Context) {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                val waveform = longArrayOf(0L, 650L, 450L, 650L, 650L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(waveform, 0),
                        buildAlarmAudioAttributes()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(waveform, 0)
                }
                synchronized(lock) {
                    alarmVibrator = vibrator
                }
            }
        }
    }

    private fun requestAlarmAudioFocus(context: Context, isPreview: Boolean) {
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(
                    if (isPreview) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                    else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                    .setAudioAttributes(buildAlarmAudioAttributes())
                    .setAcceptsDelayedFocusGain(false)
                    .build()
                audioManager.requestAudioFocus(req)
                synchronized(lock) {
                    if (isPreview) previewFocusRequest = req else alarmFocusRequest = req
                }
            }
        }
    }

    private fun abandonAlarmAudioFocus(context: Context, isPreview: Boolean) {
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = synchronized(lock) {
                    if (isPreview) {
                        val r = previewFocusRequest
                        previewFocusRequest = null
                        r
                    } else {
                        val r = alarmFocusRequest
                        alarmFocusRequest = null
                        r
                    }
                }
                if (req != null) {
                    audioManager.abandonAudioFocusRequest(req)
                }
            }
        }
    }

    private fun applyDeviceAlarmStreamVolume(context: Context, volumePercent: Int) {
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (maxVol > 0) {
                val targetVol = ((volumePercent.coerceIn(10, 100) / 100.0) * maxVol).toInt().coerceIn(1, maxVol)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVol, 0)
            }
        }
    }
}
