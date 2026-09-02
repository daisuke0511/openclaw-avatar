package com.openclaw.avatar.conversation

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal WebSocket client for OpenAI Realtime API.
 * Uses ephemeral tokens obtained from the local Termux bridge.
 */
class RealtimeClient(
    private val model: String,
    private val ephemeralToken: String,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onOpen()
        fun onSessionUpdated()
        fun onUserSpeechStarted()
        fun onUserSpeechStopped()
        fun onResponseStarted()
        fun onAudioDelta(pcm: ByteArray)
        fun onAudioDone()
        fun onUserTranscript(text: String)
        fun onAssistantText(text: String)
        fun onFunctionCall(callId: String, name: String, argumentsJson: String)
        fun onError(message: String)
        fun onClosed(code: Int, reason: String)
    }

    companion object {
        private const val TAG = "RealtimeClient"
        private const val BASE_URL = "wss://api.openai.com/v1/realtime"
    }

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null

    fun connect() {
        val url = "$BASE_URL?model=$model"
        // For ephemeral tokens on client devices, OpenAI uses WebSocket subprotocols
        // instead of the Authorization header (browsers can't set custom WS headers).
        // OkHttp can use both, but subprotocol is the officially supported path.
        val req = Request.Builder()
            .url(url)
            .addHeader("Sec-WebSocket-Protocol",
                "realtime, openai-insecure-api-key.$ephemeralToken")
            .build()
        ws = http.newWebSocket(req, listener)
    }

    fun close() {
        try { ws?.close(1000, "client shutdown") } catch (_: Exception) {}
        ws = null
    }

    fun sendAudioFrame(pcm: ByteArray) {
        val socket = ws ?: return
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val json = JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", b64)
        socket.send(json.toString())
    }

    fun cancelResponse() {
        val socket = ws ?: return
        socket.send(JSONObject().put("type", "response.cancel").toString())
    }

    fun sendFunctionResult(callId: String, resultJson: String) {
        val socket = ws ?: return
        val item = JSONObject()
            .put("type", "conversation.item.create")
            .put("item", JSONObject()
                .put("type", "function_call_output")
                .put("call_id", callId)
                .put("output", resultJson))
        socket.send(item.toString())
        // Trigger the model to continue after tool result
        socket.send(JSONObject().put("type", "response.create").toString())
    }

    fun sendSessionUpdate(tools: JSONArray?) {
        val socket = ws ?: return
        val session = JSONObject().put("type", "realtime")
        if (tools != null) {
            session.put("tools", tools)
            session.put("tool_choice", "auto")
        }
        socket.send(JSONObject()
            .put("type", "session.update")
            .put("session", session)
            .toString())
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "ws open: ${response.code} ${response.message} proto=${response.header("Sec-WebSocket-Protocol")}")
            callbacks.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val obj = JSONObject(text)
                val eventType = obj.optString("type")
                if (eventType != "response.audio.delta" && eventType != "response.audio_transcript.delta") {
                    Log.d(TAG, "event: $eventType")
                }
                when (val type = eventType) {
                    "session.created", "session.updated" -> callbacks.onSessionUpdated()
                    "input_audio_buffer.speech_started" -> callbacks.onUserSpeechStarted()
                    "input_audio_buffer.speech_stopped" -> callbacks.onUserSpeechStopped()
                    "response.created" -> callbacks.onResponseStarted()
                    "response.audio.delta" -> {
                        val delta = obj.optString("delta", "")
                        if (delta.isNotEmpty()) {
                            val pcm = Base64.decode(delta, Base64.DEFAULT)
                            callbacks.onAudioDelta(pcm)
                        }
                    }
                    "response.audio.done" -> callbacks.onAudioDone()
                    "response.done" -> callbacks.onAudioDone()
                    "conversation.item.input_audio_transcription.completed" -> {
                        val txt = obj.optString("transcript", "")
                        if (txt.isNotEmpty()) callbacks.onUserTranscript(txt)
                    }
                    "response.audio_transcript.delta" -> { /* partial text, ignore */ }
                    "response.audio_transcript.done" -> {
                        val txt = obj.optString("transcript", "")
                        if (txt.isNotEmpty()) callbacks.onAssistantText(txt)
                    }
                    "response.function_call_arguments.done" -> {
                        val name = obj.optString("name")
                        val callId = obj.optString("call_id")
                        val args = obj.optString("arguments", "{}")
                        callbacks.onFunctionCall(callId, name, args)
                    }
                    "error" -> {
                        val err = obj.optJSONObject("error")?.optString("message") ?: text
                        callbacks.onError(err)
                    }
                    else -> {
                        // ignore other events (rate_limits, response.text.delta, etc.)
                        if (type.isEmpty()) Log.d(TAG, "unknown event: ${text.take(200)}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "parse error: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val bodySnippet = try { response?.body?.string()?.take(300) } catch (_: Exception) { null }
            Log.e(TAG, "ws failure: ${t.message} code=${response?.code} body=$bodySnippet")
            callbacks.onError("ws failed ${response?.code}: ${t.message} ${bodySnippet ?: ""}")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "ws closed $code $reason")
            callbacks.onClosed(code, reason)
        }
    }
}
