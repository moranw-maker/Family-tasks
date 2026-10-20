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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

data class AlarmSoundOption(
    val soundType: String,
    val titleHe: String,
    val titleEn: String,
    val emoji: String,
    val isBuiltInMelody: Boolean
)

data class AudioPreviewState(
    val isPlaying: Boolean = false,
    val activeSoundType: String = "",
    val activeVolumePercent: Int = 85,
    val errorMessageHe: String? = null,
    val errorMessageEn: String? = null
)

/**
 * Central audio & vibration controller for FamilyTasks alarms.
 *
 * Handles:
 * 1. Immediate in-dialog sound & volume preview (with automatic stop on screen exit or timeout).
 * 2. Continuous foreground alarm ringing with `AudioAttributes.USAGE_ALARM`, audio focus,
 *    volume scaling, custom URI local caching, and safe fallback so an alarm NEVER fails silently.
 * 3. Built-in synthesized melodies (`MELODY_ENERGETIC`, `MELODY_GENTLE`, `MELODY_CLASSIC`)
 *    rendered via PCM `AudioTrack` on the `USAGE_ALARM` stream so they sound identical on every
 *    parent and child device without external file dependencies.
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
            soundType = SOUND_MELODY_ENERGETIC,
            titleHe = "מנגינת השכמה קצבית",
            titleEn = "Energetic Wake-Up Melody",
            emoji = "🎺",
            isBuiltInMelody = true
        ),
        AlarmSoundOption(
            soundType = SOUND_MELODY_GENTLE,
            titleHe = "מנגינת בוקר עדינה",
            titleEn = "Gentle Morning Chimes",
            emoji = "🌅",
            isBuiltInMelody = true
        ),
        AlarmSoundOption(
            soundType = SOUND_MELODY_CLASSIC,
            titleHe = "צפצוף שעון קלאסי",
            titleEn = "Classic Digital Alarm",
            emoji = "⏰",
            isBuiltInMelody = true
        ),
        AlarmSoundOption(
            soundType = SOUND_SYSTEM_ALARM,
            titleHe = "צליל שעון מעורר של המכשיר",
            titleEn = "Device Default Alarm",
            emoji = "🔔",
            isBuiltInMelody = false
        ),
        AlarmSoundOption(
            soundType = SOUND_SYSTEM_NOTIFICATION,
            titleHe = "צליל התראה של המכשיר",
            titleEn = "Device Notification Tone",
            emoji = "✨",
            isBuiltInMelody = false
        ),
        AlarmSoundOption(
            soundType = SOUND_SYSTEM_RINGTONE,
            titleHe = "צלצול טלפון של המכשיר",
            titleEn = "Device Ringtone",
            emoji = "📞",
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

    private var alarmJob: Job? = null
    private var alarmMediaPlayer: MediaPlayer? = null
    private var alarmRingtone: Ringtone? = null
    private var alarmAudioTrack: AudioTrack? = null
    private var alarmVibrator: Vibrator? = null
    private var alarmFocusRequest: AudioFocusRequest? = null

    private val _previewState = MutableStateFlow(AudioPreviewState())
    val previewState: StateFlow<AudioPreviewState> = _previewState.asStateFlow()

    fun soundDisplayName(soundType: String, customTitle: String, isHebrew: Boolean): String {
        if (soundType == SOUND_CUSTOM_URI) {
            return customTitle.ifBlank {
                if (isHebrew) "קובץ שמע מותאם אישית 🎵" else "Custom Audio 🎵"
            }
        }
        val opt = builtInSoundOptions.find { it.soundType == soundType }
        if (opt != null) {
            return "${opt.emoji} ${if (isHebrew) opt.titleHe else opt.titleEn}"
        }
        return if (isHebrew) "🔔 צליל שעון מעורר" else "🔔 System Alarm"
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
            // If copying fails but direct URI is readable, verify readability and use sourceUri
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
        val appCtx = context.applicationContext
        stopPreview(appCtx)

        val clampedVolume = volumePercent.coerceIn(10, 100)
        val volumeScalar = (clampedVolume / 100f).coerceIn(0.05f, 1.0f)

        requestAlarmAudioFocus(appCtx, isPreview = true)
        applyDeviceAlarmStreamVolume(appCtx, clampedVolume)

        if (soundType == SOUND_CUSTOM_URI) {
            if (customSoundUri.isBlank()) {
                _previewState.value = AudioPreviewState(
                    isPlaying = false,
                    activeSoundType = soundType,
                    activeVolumePercent = clampedVolume,
                    errorMessageHe = "לא נבחר קובץ שמע מותאם אישית",
                    errorMessageEn = "No custom audio file selected"
                )
                return
            }
            val startedCustom = startMediaPlayerInternal(
                context = appCtx,
                uri = Uri.parse(customSoundUri),
                volumeScalar = volumeScalar,
                isPreview = true
            )
            if (!startedCustom) {
                _previewState.value = AudioPreviewState(
                    isPlaying = false,
                    activeSoundType = soundType,
                    activeVolumePercent = clampedVolume,
                    errorMessageHe = "לא ניתן לנגן את קובץ השמע שנבחר. ודאו שהקובץ תקין ונגיש.",
                    errorMessageEn = "Unable to play selected audio file. Please choose another file."
                )
                return
            }
        } else if (isBuiltInMelody(soundType)) {
            startSynthesizedMelodyLoop(
                soundType = soundType,
                volumeScalar = volumeScalar,
                isPreview = true,
                maxDurationMillis = durationMillis
            )
            _previewState.value = AudioPreviewState(
                isPlaying = true,
                activeSoundType = soundType,
                activeVolumePercent = clampedVolume
            )
            return
        } else {
            val sysUri = resolveSystemToneUri(appCtx, soundType)
            val startedMedia = sysUri != null && startMediaPlayerInternal(
                context = appCtx,
                uri = sysUri,
                volumeScalar = volumeScalar,
                isPreview = true
            )
            if (!startedMedia) {
                val startedRingtone = sysUri != null && startRingtoneInternal(appCtx, sysUri, volumeScalar, isPreview = true)
                if (!startedRingtone) {
                    // Fallback to synthesized classic melody so preview is always audible
                    startSynthesizedMelodyLoop(
                        soundType = SOUND_MELODY_CLASSIC,
                        volumeScalar = volumeScalar,
                        isPreview = true,
                        maxDurationMillis = durationMillis
                    )
                }
            }
        }

        _previewState.value = AudioPreviewState(
            isPlaying = true,
            activeSoundType = soundType,
            activeVolumePercent = clampedVolume
        )

        synchronized(lock) {
            previewJob = scope.launch {
                delay(durationMillis)
                stopPreview(appCtx)
            }
        }
    }

    /**
     * Updates the volume of an actively playing preview in real time when the user drags the slider.
     */
    fun updateActivePreviewVolume(context: Context, volumePercent: Int) {
        val clamped = volumePercent.coerceIn(10, 100)
        val scalar = (clamped / 100f).coerceIn(0.05f, 1.0f)
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
            applyDeviceAlarmStreamVolume(context.applicationContext, clamped)
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
                abandonAlarmAudioFocus(context.applicationContext, isPreview = true)
            }
        }
        _previewState.value = AudioPreviewState(isPlaying = false)
    }

    /**
     * Starts continuous alarm playback + optional vibration for an active alarm trigger.
     * Guaranteed not to fail silently: if a custom URI or system tone is unavailable,
     * automatically falls back to default system alarm tone and then to synthesized PCM alarm audio.
     */
    fun startAlarmRinging(
        context: Context,
        soundType: String,
        customSoundUri: String,
        volumePercent: Int,
        vibrateEnabled: Boolean
    ) {
        val appCtx = context.applicationContext
        stopPreview(appCtx)
        stopAlarmRinging(appCtx)

        val clampedVolume = volumePercent.coerceIn(10, 100)
        val volumeScalar = (clampedVolume / 100f).coerceIn(0.1f, 1.0f)

        requestAlarmAudioFocus(appCtx, isPreview = false)
        applyDeviceAlarmStreamVolume(appCtx, clampedVolume)

        var playbackStarted = false

        if (soundType == SOUND_CUSTOM_URI && customSoundUri.isNotBlank()) {
            playbackStarted = startMediaPlayerInternal(
                context = appCtx,
                uri = Uri.parse(customSoundUri),
                volumeScalar = volumeScalar,
                isPreview = false
            )
        }

        if (!playbackStarted && isBuiltInMelody(soundType)) {
            startSynthesizedMelodyLoop(
                soundType = soundType,
                volumeScalar = volumeScalar,
                isPreview = false,
                maxDurationMillis = 5 * 60_000L
            )
            playbackStarted = true
        }

        if (!playbackStarted) {
            val sysUri = resolveSystemToneUri(appCtx, soundType)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            if (sysUri != null) {
                playbackStarted = startMediaPlayerInternal(
                    context = appCtx,
                    uri = sysUri,
                    volumeScalar = volumeScalar,
                    isPreview = false
                )
                if (!playbackStarted) {
                    playbackStarted = startRingtoneInternal(
                        context = appCtx,
                        uri = sysUri,
                        volumeScalar = volumeScalar,
                        isPreview = false
                    )
                }
            }
        }

        // Ultimate safety fallback: synthesized alarm melody on USAGE_ALARM
        if (!playbackStarted) {
            startSynthesizedMelodyLoop(
                soundType = SOUND_MELODY_ENERGETIC,
                volumeScalar = volumeScalar,
                isPreview = false,
                maxDurationMillis = 5 * 60_000L
            )
        }

        if (vibrateEnabled) {
            startContinuousVibration(appCtx)
        }
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
                abandonAlarmAudioFocus(context.applicationContext, isPreview = false)
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
            SOUND_SYSTEM_NOTIFICATION -> RingtoneManager.TYPE_NOTIFICATION
            SOUND_SYSTEM_RINGTONE -> RingtoneManager.TYPE_RINGTONE
            else -> RingtoneManager.TYPE_ALARM
        }
        return runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(context, primaryType)
                ?: RingtoneManager.getDefaultUri(primaryType)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
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
     * Synthesizes a clean musical alarm melody into a PCM 16-bit buffer and loops it on
     * `AudioAttributes.USAGE_ALARM`. Works reliably on every Android phone and emulator.
     */
    private fun startSynthesizedMelodyLoop(
        soundType: String,
        volumeScalar: Float,
        isPreview: Boolean,
        maxDurationMillis: Long
    ) {
        val sampleRate = 24000
        val patternNotes: List<Pair<Double, Int>> = when (soundType) {
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

        val pcmSamples = synthesizeMelodyPcm(sampleRate, patternNotes)
        val byteCount = pcmSamples.size * 2

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
                    .setBufferSizeInBytes(maxOf(byteCount, AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )))
                    .setTransferMode(AudioTrack.MODE_STATIC)
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
                track.write(pcmSamples, 0, pcmSamples.size)
                track.setLoopPoints(0, pcmSamples.size, -1)
                track.setVolume(volumeScalar)
                track.play()
            }

            val startMillis = System.currentTimeMillis()
            while (isActive && (System.currentTimeMillis() - startMillis) < maxDurationMillis) {
                delay(250L)
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
                    // Fundamental + soft octave overtone for rich bell-like clarity
                    val wave = 0.78 * sin(2.0 * PI * freq * t) + 0.22 * sin(2.0 * PI * (freq * 2.0) * t)
                    val sample = (wave * envelope * 22000.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
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
