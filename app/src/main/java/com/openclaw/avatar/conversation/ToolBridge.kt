package com.openclaw.avatar.conversation

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Exposes a curated set of OpenClaw-backed function tools to the Realtime model.
 * Each tool is proxied through openclaw-room / avatar bridge / termux-api commands
 * via HTTP so we don't need to embed OpenClaw logic in the Android app.
 */
class ToolBridge(
    private val roomBase: String = "http://127.0.0.1:8787",
    private val avatarBase: String = "http://127.0.0.1:8791",
) {

    private val http = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    fun toolSpecs(): JSONArray {
        val arr = JSONArray()
        arr.put(tool("get_battery_status",
            "Get the phone's current battery percentage and charging state.",
            JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray())))
        arr.put(tool("get_phone_state",
            "Get an overview of the phone: battery, network, storage, sensors.",
            JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray())))
        arr.put(tool("show_notification",
            "Display a system notification on the phone.",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("title", JSONObject().put("type", "string"))
                    .put("content", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("title").put("content"))))
        arr.put(tool("set_avatar_state",
            "Change the avatar's expression to reflect the current mood or activity.",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("state", JSONObject()
                        .put("type", "string")
                        .put("enum", JSONArray().apply {
                            listOf("HAPPY", "TASK_COMPLETED", "GREETING", "AFFECTION",
                                "HEART", "SURPRISED", "THINKING", "SLEEPING").forEach { put(it) }
                        })))
                .put("required", JSONArray().put("state"))))
        return arr
    }

    private fun tool(name: String, description: String, parameters: JSONObject): JSONObject =
        JSONObject()
            .put("type", "function")
            .put("name", name)
            .put("description", description)
            .put("parameters", parameters)

    /**
     * Executes a tool call. Returns a JSON string safe for `function_call_output.output`.
     */
    fun execute(name: String, argsJson: String): String {
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        return try {
            when (name) {
                "get_battery_status" -> getJson("$roomBase/api/status")
                "get_phone_state"    -> postJson("$roomBase/api/action", JSONObject().put("action", "phone-state").toString())
                "show_notification"  -> postJson("$roomBase/api/action", JSONObject()
                    .put("action", "notify")
                    .put("title", args.optString("title", "OpenClaw"))
                    .put("content", args.optString("content", ""))
                    .toString())
                "set_avatar_state"   -> postJson("$avatarBase/avatar/state", JSONObject()
                    .put("state", args.optString("state", "HAPPY"))
                    .toString())
                else -> """{"error":"unknown tool: $name"}"""
            }
        } catch (e: Exception) {
            """{"error":"${e.message?.replace("\"", "'")}"}"""
        }
    }

    private fun getJson(url: String): String {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            return resp.body?.string()?.take(4000) ?: """{"ok":${resp.isSuccessful}}"""
        }
    }

    private fun postJson(url: String, bodyJson: String): String {
        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            return resp.body?.string()?.take(4000) ?: """{"ok":${resp.isSuccessful}}"""
        }
    }
}
