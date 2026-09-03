package com.openclaw.avatar.conversation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.openclaw.avatar.system.AvatarAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device-side tools invoked from the Realtime model via function-calling.
 * Kept out of ToolBridge so it can hold a Context and PackageManager.
 */
class DeviceTools(private val ctx: Context) {

    fun specs(): JSONArray {
        val arr = JSONArray()
        arr.put(tool("open_app",
            "Launch an installed app by its exact package name (e.g. 'com.android.chrome'). " +
            "Use list_installed_apps if you need to find one first.",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("package", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("package"))))
        arr.put(tool("list_installed_apps",
            "List launchable apps installed on the phone. Returns package + label pairs.",
            JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray())))
        arr.put(tool("open_url",
            "Open a URL in the default browser. Also works for map / tel: / mailto: URIs.",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("url", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("url"))))
        arr.put(tool("search_web",
            "Open a Google search for the given query in the default browser.",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("query", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("query"))))
        arr.put(tool("vibrate_phone",
            "Vibrate the phone briefly (default 300 ms).",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("duration_ms", JSONObject().put("type", "integer")))
                .put("required", JSONArray())))
        arr.put(tool("toggle_flashlight",
            "Turn the phone's flashlight on or off.",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("on", JSONObject().put("type", "boolean")))
                .put("required", JSONArray().put("on"))))
        arr.put(tool("set_media_volume",
            "Set the media volume as a percentage (0-100).",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("percent", JSONObject().put("type", "integer")))
                .put("required", JSONArray().put("percent"))))
        // Accessibility-based gestures (only work if a11y service is enabled)
        arr.put(tool("tap_screen",
            "Tap the screen at pixel coordinates (x, y). Requires accessibility permission. " +
            "Screen origin is top-left; call get_screen_size first if you don't know dimensions.",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("x", JSONObject().put("type", "number"))
                    .put("y", JSONObject().put("type", "number")))
                .put("required", JSONArray().put("x").put("y"))))
        arr.put(tool("swipe_screen",
            "Swipe from (x1,y1) to (x2,y2) over duration_ms (default 300).",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("x1", JSONObject().put("type", "number"))
                    .put("y1", JSONObject().put("type", "number"))
                    .put("x2", JSONObject().put("type", "number"))
                    .put("y2", JSONObject().put("type", "number"))
                    .put("duration_ms", JSONObject().put("type", "integer")))
                .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2"))))
        arr.put(tool("press_button",
            "Press a system button. Values: back, home, recents, notifications, quick_settings.",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("button", JSONObject().put("type", "string")
                        .put("enum", JSONArray().apply {
                            listOf("back","home","recents","notifications","quick_settings").forEach { put(it) }
                        })))
                .put("required", JSONArray().put("button"))))
        arr.put(tool("get_screen_size",
            "Returns the phone screen dimensions in pixels {width, height} for coordinate math.",
            JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray())))
        return arr
    }

    private fun tool(name: String, description: String, parameters: JSONObject): JSONObject =
        JSONObject().put("type", "function")
            .put("name", name)
            .put("description", description)
            .put("parameters", parameters)

    fun canHandle(name: String): Boolean = when (name) {
        "open_app", "list_installed_apps", "open_url", "search_web",
        "vibrate_phone", "toggle_flashlight", "set_media_volume",
        "tap_screen", "swipe_screen", "press_button", "get_screen_size" -> true
        else -> false
    }

    fun execute(name: String, argsJson: String): String {
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        return try {
            when (name) {
                "open_app"            -> openApp(args.optString("package"))
                "list_installed_apps" -> listApps()
                "open_url"            -> openUrl(args.optString("url"))
                "search_web"          -> searchWeb(args.optString("query"))
                "vibrate_phone"       -> vibrate(args.optInt("duration_ms", 300))
                "toggle_flashlight"   -> toggleFlashlight(args.optBoolean("on", true))
                "set_media_volume"    -> setMediaVolume(args.optInt("percent", 50))
                "tap_screen"          -> tapScreen(args.optDouble("x").toFloat(),
                                                    args.optDouble("y").toFloat())
                "swipe_screen"        -> swipeScreen(
                    args.optDouble("x1").toFloat(), args.optDouble("y1").toFloat(),
                    args.optDouble("x2").toFloat(), args.optDouble("y2").toFloat(),
                    args.optInt("duration_ms", 300).toLong())
                "press_button"        -> pressButton(args.optString("button"))
                "get_screen_size"     -> getScreenSize()
                else -> """{"error":"unknown tool"}"""
            }
        } catch (e: Exception) {
            """{"error":"${e.message?.replace("\"", "'")}"}"""
        }
    }

    // --- Individual actions ---

    private fun openApp(pkg: String): String {
        if (pkg.isBlank()) return """{"error":"package required"}"""
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return """{"error":"app not installed: $pkg"}"""
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return """{"ok":true,"launched":"$pkg"}"""
    }

    private fun listApps(): String {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            PackageManager.ResolveInfoFlags.of(0L).let { 0 } // fallback to int overload
        else 0
        val activities = pm.queryIntentActivities(intent, flags)
        val arr = JSONArray()
        // Cap at 60 to keep response short
        for (r in activities.take(60)) {
            val pkg = r.activityInfo?.packageName ?: continue
            val label = r.loadLabel(pm).toString()
            arr.put(JSONObject().put("package", pkg).put("label", label))
        }
        return JSONObject().put("apps", arr).toString()
    }

    private fun openUrl(url: String): String {
        if (url.isBlank()) return """{"error":"url required"}"""
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return """{"ok":true,"opened":"$url"}"""
    }

    private fun searchWeb(query: String): String {
        if (query.isBlank()) return """{"error":"query required"}"""
        val encoded = Uri.encode(query)
        return openUrl("https://www.google.com/search?q=$encoded")
    }

    private fun vibrate(ms: Int): String {
        val duration = ms.coerceIn(50, 2000).toLong()
        val vib: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vib.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        return """{"ok":true,"vibrated_ms":$duration}"""
    }

    private fun toggleFlashlight(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { camId ->
            cm.getCameraCharacteristics(camId)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return """{"error":"no flashlight"}"""
        cm.setTorchMode(id, on)
        return """{"ok":true,"flashlight":$on}"""
    }

    private fun setMediaVolume(percent: Int): String {
        val p = percent.coerceIn(0, 100)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * p / 100.0).toInt()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return """{"ok":true,"volume":$target,"max":$max}"""
    }

    // --- Accessibility-based screen control ---

    private fun a11yOrError(): AvatarAccessibilityService? {
        val svc = AvatarAccessibilityService.instance
        return svc
    }

    private fun tapScreen(x: Float, y: Float): String {
        val svc = a11yOrError() ?: return """{"error":"accessibility service not enabled — user must turn on makana in Settings→Accessibility"}"""
        return if (svc.tapAt(x, y)) """{"ok":true,"tapped":[$x,$y]}"""
               else """{"error":"gesture dispatch failed"}"""
    }

    private fun swipeScreen(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): String {
        val svc = a11yOrError() ?: return """{"error":"accessibility service not enabled"}"""
        return if (svc.swipe(x1, y1, x2, y2, duration)) """{"ok":true}"""
               else """{"error":"gesture dispatch failed"}"""
    }

    private fun pressButton(button: String): String {
        val svc = a11yOrError() ?: return """{"error":"accessibility service not enabled"}"""
        val ok = when (button.lowercase()) {
            "back"           -> svc.globalBack()
            "home"           -> svc.globalHome()
            "recents"        -> svc.globalRecents()
            "notifications"  -> svc.globalNotifications()
            "quick_settings" -> svc.globalQuickSettings()
            else -> return """{"error":"unknown button: $button"}"""
        }
        return """{"ok":$ok,"button":"$button"}"""
    }

    @Suppress("DEPRECATION")
    private fun getScreenSize(): String {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return """{"width":${dm.widthPixels},"height":${dm.heightPixels},"density":${dm.density}}"""
    }
}
