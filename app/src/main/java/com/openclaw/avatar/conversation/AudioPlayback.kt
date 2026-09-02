package com.openclaw.avatar.conversation

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming 24kHz PCM16 playback. write() enqueues chunks; a dedicated thread
 * pipes them to AudioTrack. stopAndFlush() drops all pending audio (used for barge-in).
 */
class AudioPlayback {
    companion object {
        private const val TAG = "AudioPlayback"
        const val SAMPLE_RATE = 24000
    }

    private val running = AtomicBoolean(false)
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var totalWrittenBytes: Long = 0
    @Volatile private var totalPlayedBytes: Long = 0

    /** Approximate elapsed ms since the currently-playing utterance started. */
    val playbackPositionMs: Long
        get() = (totalPlayedBytes * 1000L) / (SAMPLE_RATE * 2)

    val isPlaying: Boolean get() = running.get() && queue.isNotEmpty()

    fun start(): Boolean {
        if (running.get()) return true
        val channelCfg = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, channelCfg, encoding)
        val bufSize = maxOf(minBuf, SAMPLE_RATE * 2 / 5) // ~200ms
        val at = try {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(channelCfg)
                    .build(),
                bufSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack ctor failed: ${e.message}")
            return false
        }
        if (at.state != AudioTrack.STATE_INITIALIZED) {
            at.release(); return false
        }
        track = at
        at.play()
        running.set(true)
        thread = Thread({
            while (running.get()) {
                val chunk = queue.poll()
                if (chunk == null) {
                    Thread.sleep(5); continue
                }
                var offset = 0
                while (offset < chunk.size && running.get()) {
                    val written = try { at.write(chunk, offset, chunk.size - offset) } catch (_: Exception) { -1 }
                    if (written <= 0) break
                    offset += written
                    totalPlayedBytes += written
                }
            }
        }, "audio-playback").apply {
            priority = Thread.MAX_PRIORITY - 1
            isDaemon = true
            start()
        }
        return true
    }

    fun enqueue(pcm: ByteArray) {
        if (!running.get()) return
        totalWrittenBytes += pcm.size
        queue.offer(pcm)
    }

    /** Immediately discards all buffered audio (for barge-in). */
    fun stopAndFlush() {
        queue.clear()
        try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
        totalWrittenBytes = 0
        totalPlayedBytes = 0
    }

    fun stop() {
        if (!running.get()) return
        running.set(false)
        queue.clear()
        try { track?.pause() } catch (_: Exception) {}
        try { track?.flush() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        try { thread?.join(300) } catch (_: Exception) {}
        thread = null
    }
}
