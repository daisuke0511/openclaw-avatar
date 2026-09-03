package com.openclaw.avatar.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Accessibility Service that lets the AI tap and swipe on the screen.
 * The user must enable this in system settings (Settings → Accessibility → makana).
 *
 * Singleton reference is exposed so DeviceTools can dispatch gestures.
 */
class AvatarAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't consume events; we only issue gestures & global actions.
    }

    override fun onInterrupt() {}

    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun longPressAt(x: Float, y: Float, durationMs: Long = 600L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /** Snapshot the visible screen as a JSON tree of text + clickable elements. */
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return """{"error":"no active window"}"""
        val texts = JSONArray()
        val clickables = JSONArray()
        val editables = JSONArray()
        walk(root, texts, clickables, editables, depth = 0, maxDepth = 30)
        val pkg = root.packageName?.toString() ?: ""
        return JSONObject()
            .put("app", pkg)
            .put("texts", texts)
            .put("editable_fields", editables)
            .put("clickable_elements", clickables)
            .toString()
    }

    private fun walk(
        node: AccessibilityNodeInfo?,
        texts: JSONArray, clickables: JSONArray, editables: JSONArray,
        depth: Int, maxDepth: Int
    ) {
        if (node == null || depth > maxDepth) return
        try {
            val label = node.text?.toString()
            val hint = node.hintText?.toString()
            val desc = node.contentDescription?.toString()
            val rect = Rect().also { node.getBoundsInScreen(it) }
            if (node.isEditable) {
                editables.put(JSONObject()
                    .put("text", label ?: "")
                    .put("hint", hint ?: "")
                    .put("cx", rect.centerX())
                    .put("cy", rect.centerY()))
            } else if (!label.isNullOrBlank() && texts.length() < 60) {
                texts.put(label)
            } else if (!desc.isNullOrBlank() && texts.length() < 60) {
                texts.put(desc)
            }
            if (node.isClickable && clickables.length() < 40) {
                clickables.put(JSONObject()
                    .put("text", label ?: desc ?: "")
                    .put("cx", rect.centerX())
                    .put("cy", rect.centerY()))
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), texts, clickables, editables, depth + 1, maxDepth)
            }
        } catch (_: Exception) {}
    }

    fun globalBack(): Boolean    = performGlobalAction(GLOBAL_ACTION_BACK)
    fun globalHome(): Boolean    = performGlobalAction(GLOBAL_ACTION_HOME)
    fun globalRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun globalNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun globalQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    companion object {
        private const val TAG = "AvatarA11y"
        @Volatile var instance: AvatarAccessibilityService? = null
            private set
        fun isEnabled(): Boolean = instance != null
    }
}
