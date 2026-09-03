package com.openclaw.avatar.conversation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.openclaw.avatar.MainActivity
import com.openclaw.avatar.events.AvatarEvent
import com.openclaw.avatar.events.EventBus
import com.openclaw.avatar.events.Priority
import com.openclaw.avatar.events.SemanticState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ConversationService : Service() {

    companion object {
        private const val TAG = "ConversationService"
        private const val CHANNEL_ID = "openclaw_conversation_channel"
        private const val NOTIFICATION_ID = 1002
        const val ACTION_START = "com.openclaw.avatar.CONV_START"
        const val ACTION_STOP  = "com.openclaw.avatar.CONV_STOP"
        const val SILENCE_TIMEOUT_MS = 120_000L

        @Volatile private var running: Boolean = false
        fun isActive(): Boolean = running
    }

    private val main = Handler(Looper.getMainLooper())
    private val state = AtomicReference(ConversationState.OFF)

    private var capture: AudioCapture? = null
    private var playback: AudioPlayback? = null
    private var realtime: RealtimeClient? = null
    private var toolBridge: ToolBridge? = null
    private var silenceCheck: Runnable? = null
    private var lastVoiceActivity: Long = 0L

    private val http = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); stopSelf(); return START_NOT_STICKY }
            else -> startAsForeground()
        }
        if (!running) {
            running = true
            startConversation()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopEverything()
        super.onDestroy()
    }

    // --- Foreground boilerplate ---

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "OpenClaw Conversation", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun startAsForeground() {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("makana")
            .setContentText("会話中")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi).setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIFICATION_ID, n)
    }

    // --- Conversation lifecycle ---

    private fun startConversation() {
        transition(ConversationState.CONNECTING)
        Thread({
            val tokenResp = fetchEphemeralToken()
            if (tokenResp == null) {
                ConversationLog.error(TAG, "ephemeral token fetch failed")
                transition(ConversationState.ERROR)
                main.postDelayed({ stopSelf() }, 2000)
                return@Thread
            }
            val (token, model) = tokenResp

            // Route audio to loud-speaker + set voice call mode for AEC
            (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.let { am ->
                try {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    am.isSpeakerphoneOn = true
                } catch (_: Exception) {}
            }

            val pb = AudioPlayback().also { it.start() }
            playback = pb

            val bridge = ToolBridge(deviceTools = DeviceTools(this@ConversationService.applicationContext))
            toolBridge = bridge

            val rt = RealtimeClient(model, token, object : RealtimeClient.Callbacks {
                override fun onOpen() {
                    ConversationLog.log(TAG, "T-open: ${System.currentTimeMillis()}")
                    // Register tool schemas
                    realtime?.sendSessionUpdate(bridge.toolSpecs())
                    transition(ConversationState.LISTENING)
                    lastVoiceActivity = System.currentTimeMillis()
                    startSilenceWatchdog()
                }
                override fun onSessionUpdated() {}
                override fun onUserSpeechStarted() {
                    ConversationLog.log(TAG, "T-userStart: ${System.currentTimeMillis()}")
                    // Barge-in: user began talking → cancel any in-flight response and drop TTS
                    if (state.get() == ConversationState.AI_SPEAKING) {
                        pb.stopAndFlush()
                        realtime?.cancelResponse()
                        transition(ConversationState.INTERRUPTED)
                        main.postDelayed({ transition(ConversationState.USER_SPEAKING) }, 200)
                    } else {
                        transition(ConversationState.USER_SPEAKING)
                    }
                    lastVoiceActivity = System.currentTimeMillis()
                }
                override fun onUserSpeechStopped() {
                    ConversationLog.log(TAG, "T-userStop: ${System.currentTimeMillis()}")
                    transition(ConversationState.THINKING)
                    lastVoiceActivity = System.currentTimeMillis()
                }
                override fun onResponseStarted() {
                    ConversationLog.log(TAG, "T-responseStart: ${System.currentTimeMillis()}")
                }
                override fun onAudioDelta(pcm: ByteArray) {
                    if (state.get() != ConversationState.AI_SPEAKING) {
                        ConversationLog.log(TAG, "T-firstAudio: ${System.currentTimeMillis()}")
                        transition(ConversationState.AI_SPEAKING)
                    }
                    pb.enqueue(pcm)
                    lastVoiceActivity = System.currentTimeMillis()
                }
                override fun onAudioDone() {
                    if (state.get() == ConversationState.AI_SPEAKING) {
                        transition(ConversationState.LISTENING)
                    }
                }
                override fun onUserTranscript(text: String) {
                    ConversationLog.log(TAG, "user: $text")
                }
                override fun onAssistantText(text: String) {
                    ConversationLog.log(TAG, "ai: $text")
                }
                override fun onFunctionCall(callId: String, name: String, argumentsJson: String) {
                    transition(ConversationState.TOOL_CALLING)
                    Thread({
                        val result = try { bridge.execute(name, argumentsJson) }
                            catch (e: Exception) { """{"error":"${e.message}"}""" }
                        realtime?.sendFunctionResult(callId, result)
                        transition(ConversationState.THINKING)
                    }, "tool-call").apply { isDaemon = true; start() }
                }
                override fun onError(message: String) {
                    ConversationLog.error(TAG, "realtime error: $message")
                    transition(ConversationState.ERROR)
                    main.postDelayed({ stopSelf() }, 3000)
                }
                override fun onClosed(code: Int, reason: String) {
                    ConversationLog.log(TAG, "realtime closed: $code $reason")
                    if (running) {
                        transition(ConversationState.OFF)
                        stopSelf()
                    }
                }
            })
            realtime = rt
            rt.connect()

            // Start capturing after WS connect (audio buffers queued in OKHTTP until open)
            val cap = AudioCapture { frame ->
                realtime?.sendAudioFrame(frame)
            }
            if (!cap.start()) {
                ConversationLog.error(TAG, "microphone unavailable")
                transition(ConversationState.ERROR)
                main.postDelayed({ stopSelf() }, 2000)
                return@Thread
            }
            capture = cap
        }, "conv-start").apply { isDaemon = true; start() }
    }

    private fun fetchEphemeralToken(): Pair<String, String>? {
        return try {
            val req = Request.Builder()
                .url("http://127.0.0.1:8787/realtime/session")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val obj = JSONObject(body)
                val secret = obj.optJSONObject("client_secret")?.optString("value") ?: return null
                val model = obj.optString("model", "gpt-4o-realtime-preview")
                secret to model
            }
        } catch (e: Exception) {
            ConversationLog.error(TAG, "token fetch: ${e.message}")
            null
        }
    }

    private fun startSilenceWatchdog() {
        stopSilenceWatchdog()
        val runnable = object : Runnable {
            override fun run() {
                val idle = System.currentTimeMillis() - lastVoiceActivity
                if (idle > SILENCE_TIMEOUT_MS && running) {
                    ConversationLog.log(TAG, "silence timeout, stopping")
                    stopSelf()
                    return
                }
                main.postDelayed(this, 5000)
            }
        }
        silenceCheck = runnable
        main.postDelayed(runnable, 5000)
    }

    private fun stopSilenceWatchdog() {
        silenceCheck?.let { main.removeCallbacks(it) }
        silenceCheck = null
    }

    private fun stopEverything() {
        stopSilenceWatchdog()
        try { capture?.stop() } catch (_: Exception) {}
        try { playback?.stop() } catch (_: Exception) {}
        try { realtime?.close() } catch (_: Exception) {}
        capture = null; playback = null; realtime = null; toolBridge = null
        (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.let { am ->
            try { am.mode = AudioManager.MODE_NORMAL; am.isSpeakerphoneOn = false } catch (_: Exception) {}
        }
        state.set(ConversationState.OFF)
        // Emit final semantic reset so avatar returns to idle
        EventBus.publish(AvatarEvent.Semantic(
            state = SemanticState.HAPPY, durationMs = 1500L,
            priority = Priority.SYSTEM_EVENT, source = "conversation"
        ))
    }

    private fun transition(next: ConversationState) {
        val prev = state.getAndSet(next)
        if (prev == next) return
        ConversationLog.log(TAG, "state: $prev -> $next")
        // Emit corresponding semantic (avatar mirrors conversation state)
        val (sem, prio) = when (next) {
            ConversationState.CONNECTING    -> SemanticState.SURPRISED to Priority.SYSTEM_EVENT
            ConversationState.LISTENING     -> SemanticState.HAPPY to Priority.OPENCLAW_ACTION
            ConversationState.USER_SPEAKING -> SemanticState.SURPRISED to Priority.OPENCLAW_ACTION
            ConversationState.THINKING      -> SemanticState.THINKING to Priority.OPENCLAW_ACTION
            ConversationState.TOOL_CALLING  -> SemanticState.WORKING to Priority.OPENCLAW_ACTION
            ConversationState.AI_SPEAKING   -> SemanticState.SPEAKING to Priority.OPENCLAW_ACTION
            ConversationState.INTERRUPTED   -> SemanticState.SURPRISED to Priority.USER_INTERACTION
            ConversationState.ERROR         -> SemanticState.ERROR to Priority.SYSTEM_EVENT
            ConversationState.OFF           -> return
        }
        EventBus.publish(AvatarEvent.Semantic(
            state = sem, durationMs = null,
            priority = prio, source = "conversation:${next.name}"
        ))
    }
}
