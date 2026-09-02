package com.openclaw.avatar.conversation

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 24kHz PCM16 mono capture with AEC + NS applied.
 * Emits fixed-size frames (~20 ms = 480 samples = 960 bytes) via [onFrame].
 * Runs on a dedicated thread.
 */
class AudioCapture(
    private val onFrame: (ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 24000
        private const val FRAME_SAMPLES = 480 // 20ms @ 24kHz
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running.get()) return true
        val channelCfg = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelCfg, encoding)
        val bufSize = maxOf(minBuf, FRAME_BYTES * 8)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, channelCfg, encoding, bufSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord ctor failed: ${e.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized")
            rec.release()
            return false
        }
        val sessionId = rec.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
        }
        record = rec
        rec.startRecording()
        running.set(true)
        thread = Thread({
            val buf = ByteArray(FRAME_BYTES)
            while (running.get()) {
                var readTotal = 0
                while (readTotal < FRAME_BYTES && running.get()) {
                    val n = rec.read(buf, readTotal, FRAME_BYTES - readTotal)
                    if (n < 0) { break }
                    readTotal += n
                }
                if (readTotal == FRAME_BYTES) {
                    onFrame(buf.copyOf())
                }
            }
        }, "audio-capture").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        if (!running.get()) return
        running.set(false)
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        try { aec?.release() } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        record = null; aec = null; ns = null
        try { thread?.join(300) } catch (_: Exception) {}
        thread = null
    }
}
