package org.ledfxmobile.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.max

enum class AudioSourceType { MICROPHONE, PHONE_AUDIO }

/**
 * Reads mono float PCM from the microphone, or from audio other apps are playing (Android 10+
 * playback capture), and feeds it to an [AudioAnalyzer] on a dedicated thread.
 */
class AudioCapture(context: Context, private val analyzer: AudioAnalyzer) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var record: AudioRecord? = null
    private var reader: Thread? = null
    @Volatile private var running = false

    /** Human-readable reason when capture isn't working, or null when it is. */
    @Volatile var problem: String? = null
        private set

    fun start(source: AudioSourceType, projection: MediaProjection?) {
        stop()
        synchronized(lock) {
            problem = null
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                problem = "Microphone permission not granted"
                return
            }
            if (source == AudioSourceType.PHONE_AUDIO && projection == null) {
                problem = "Allow phone audio capture to use this source"
                return
            }
            val r = try {
                build(source, projection)
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord failed", e); null
            }
            if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
                r?.release()
                problem = "Could not open audio input"
                return
            }
            r.startRecording()
            record = r
            running = true
            reader = thread(name = "audio-capture", priority = Thread.MAX_PRIORITY) { readLoop(r) }
            Log.i(TAG, "Capturing $source")
        }
    }

    fun stop() {
        val t: Thread?
        synchronized(lock) {
            running = false
            record?.let {
                try { it.stop() } catch (_: Exception) {}
            }
            t = reader
            reader = null
        }
        t?.join(500)
        synchronized(lock) {
            record?.release()
            record = null
        }
        analyzer.reset()
    }

    private fun readLoop(r: AudioRecord) {
        val buf = FloatArray(AudioAnalyzer.SAMPLE_RATE / 100) // 10 ms
        while (running) {
            val n = r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            if (n > 0) analyzer.push(buf, n) else if (n < 0) break
        }
    }

    @Suppress("MissingPermission")
    private fun build(source: AudioSourceType, projection: MediaProjection?): AudioRecord {
        val rate = AudioAnalyzer.SAMPLE_RATE
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBytes = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val builder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minBytes, rate / 10 * 4))
        if (source == AudioSourceType.PHONE_AUDIO) {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            builder.setAudioPlaybackCaptureConfig(config)
        } else {
            val am = appContext.getSystemService(AudioManager::class.java)
            // UNPROCESSED skips voice processing (auto-gain, noise suppression) that flattens music.
            val src = if (am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
                MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
            builder.setAudioSource(src)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "AudioCapture"
    }
}
