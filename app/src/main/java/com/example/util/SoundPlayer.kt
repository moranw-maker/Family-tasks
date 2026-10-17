package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.util.Log
import com.example.model.AlertSound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sound manager providing crystal-clear audio preview and playback for notification chimes,
 * system tones, and user-selected custom audio files from device storage.
 *
 * Uses synthesized PCM waveforms over AudioTrack (STREAM_MUSIC) to guarantee audible playback
 * on all Android physical devices and emulators, alongside MediaPlayer for custom audio files.
 */
object SoundPlayer {
    private const val TAG = "SoundPlayer"
    private var mediaPlayer: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null

    /**
     * Plays a preview of the specified [AlertSound] or custom audio URI.
     *
     * @param context Android context
     * @param sound The chosen [AlertSound] preset
     * @param customUri Optional content URI to a local audio file
     * @param volume Volume level from 0.0f to 1.0f
     */
    fun playSound(
        context: Context,
        sound: AlertSound,
        customUri: String? = null,
        volume: Float = 0.85f
    ) {
        stop()

        val safeVolume = volume.coerceIn(0.05f, 1.0f)

        // 1. If custom URI provided, play with MediaPlayer
        if (!customUri.isNullOrBlank()) {
            try {
                val uri = Uri.parse(customUri)
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(context, uri)
                    setVolume(safeVolume, safeVolume)
                    setOnCompletionListener {
                        stop()
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                        stop()
                        true
                    }
                    prepare()
                    start()
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "Could not play custom audio URI: ${e.message}, falling back to preset tone")
            }
        }

        // 2. Play preset tone using high-fidelity synthesized AudioTrack on STREAM_MUSIC
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val pcmData = generateChimePcm(sound, safeVolume)
                playPcmAudio(pcmData)
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack synthesis error: ${e.message}, falling back to ToneGenerator", e)
                playToneGeneratorFallback(context, sound, safeVolume)
            }
        }
    }

    /**
     * Synthesizes pleasing harmonic tones for preset alerts.
     */
    private fun generateChimePcm(sound: AlertSound, volume: Float): ShortArray {
        val sampleRate = 44100
        return when (sound) {
            AlertSound.GOOGLE_CHIME -> {
                // Cheerful 2-tone melodic Google chime: G5 (784Hz) -> C6 (1046.5Hz)
                val part1Samples = (sampleRate * 0.12).toInt()
                val part2Samples = (sampleRate * 0.28).toInt()
                val total = part1Samples + part2Samples
                val buffer = ShortArray(total)

                for (i in 0 until part1Samples) {
                    val t = i.toDouble() / sampleRate
                    val envelope = (1.0 - (i.toDouble() / part1Samples) * 0.3)
                    val sample = (sin(2.0 * Math.PI * 783.99 * t) * envelope * Short.MAX_VALUE * volume * 0.8).toInt()
                    buffer[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                for (i in 0 until part2Samples) {
                    val t = i.toDouble() / sampleRate
                    val envelope = exp(-t * 8.0)
                    val s1 = sin(2.0 * Math.PI * 1046.50 * t)
                    val s2 = 0.25 * sin(2.0 * Math.PI * 2093.00 * t) // octave shimmer
                    val sample = ((s1 + s2) * envelope * Short.MAX_VALUE * volume * 0.8).toInt()
                    buffer[part1Samples + i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                buffer
            }
            AlertSound.BRIGHT_BELL -> {
                // Crystal bell tone at 880 Hz (A5) with bell harmonic (1760 Hz)
                val duration = 0.35
                val numSamples = (sampleRate * duration).toInt()
                val buffer = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val envelope = exp(-t * 7.5)
                    val s1 = sin(2.0 * Math.PI * 880.0 * t)
                    val s2 = 0.4 * sin(2.0 * Math.PI * 1760.0 * t)
                    val s3 = 0.15 * sin(2.0 * Math.PI * 2640.0 * t)
                    val sample = ((s1 + s2 + s3) * envelope * Short.MAX_VALUE * volume * 0.75).toInt()
                    buffer[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                buffer
            }
            AlertSound.POP_BUBBLE -> {
                // Fast cheerful bubble frequency sweep upwards 420 Hz -> 860 Hz
                val duration = 0.14
                val numSamples = (sampleRate * duration).toInt()
                val buffer = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val progress = i.toDouble() / numSamples
                    val freq = 420.0 + (860.0 - 420.0) * progress * progress
                    val t = i.toDouble() / sampleRate
                    val envelope = sin(Math.PI * progress) // rounded attack and decay
                    val sample = (sin(2.0 * Math.PI * freq * t) * envelope * Short.MAX_VALUE * volume * 0.85).toInt()
                    buffer[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                buffer
            }
            AlertSound.COSMIC_TONE -> {
                // Sci-fi shimmering cosmic chime with gentle pitch modulation
                val duration = 0.35
                val numSamples = (sampleRate * duration).toInt()
                val buffer = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    val vibrato = 8.0 * sin(2.0 * Math.PI * 12.0 * t)
                    val freq = 587.33 + vibrato
                    val envelope = exp(-t * 6.5)
                    val s1 = sin(2.0 * Math.PI * freq * t)
                    val s2 = 0.3 * sin(2.0 * Math.PI * (freq * 1.5) * t)
                    val sample = ((s1 + s2) * envelope * Short.MAX_VALUE * volume * 0.8).toInt()
                    buffer[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                buffer
            }
            AlertSound.GENTLE_HARP -> {
                // Soothing 3-note harp arpeggio: C5 (523Hz), E5 (659Hz), G5 (784Hz)
                val noteLen = (sampleRate * 0.10).toInt()
                val ringOut = (sampleRate * 0.20).toInt()
                val total = noteLen * 3 + ringOut
                val buffer = ShortArray(total)
                val freqs = doubleArrayOf(523.25, 659.25, 783.99)

                for (n in 0..2) {
                    val startIdx = n * noteLen
                    val noteFreq = freqs[n]
                    for (i in 0 until (noteLen + ringOut)) {
                        val currentIdx = startIdx + i
                        if (currentIdx >= total) break
                        val t = i.toDouble() / sampleRate
                        val envelope = exp(-t * 9.0)
                        val s = (sin(2.0 * Math.PI * noteFreq * t) * envelope * Short.MAX_VALUE * volume * 0.7).toInt()
                        val combined = buffer[currentIdx] + s
                        buffer[currentIdx] = combined.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
                buffer
            }
        }
    }

    private fun playPcmAudio(pcmData: ShortArray) {
        val sampleRate = 44100
        val bufferSize = pcmData.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcmData, 0, pcmData.size)
        track.play()
        audioTrack = track
    }

    private fun playToneGeneratorFallback(context: Context, sound: AlertSound, volume: Float) {
        val streamVolume = ((volume.coerceIn(0.1f, 1.0f)) * 100).toInt()
        try {
            // Use STREAM_MUSIC so it is audible through emulator media channel
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, streamVolume)
            val toneType = when (sound) {
                AlertSound.GOOGLE_CHIME -> ToneGenerator.TONE_PROP_BEEP2
                AlertSound.BRIGHT_BELL -> ToneGenerator.TONE_PROP_PROMPT
                AlertSound.POP_BUBBLE -> ToneGenerator.TONE_PROP_BEEP
                AlertSound.COSMIC_TONE -> ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE
                AlertSound.GENTLE_HARP -> ToneGenerator.TONE_PROP_ACK
            }
            toneGen.startTone(toneType, 280)
            CoroutineScope(Dispatchers.Default).launch {
                kotlinx.coroutines.delay(350)
                try { toneGen.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            try {
                val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val ringtone = RingtoneManager.getRingtone(context, defaultUri)
                ringtone?.play()
            } catch (_: Exception) {}
        }
    }

    /**
     * Stops any currently playing audio preview.
     */
    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }
}
