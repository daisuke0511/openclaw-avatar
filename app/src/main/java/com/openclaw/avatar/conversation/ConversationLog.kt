package com.openclaw.avatar.conversation

import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-memory ring buffer for the last N conversation events, so the Termux side
 * can pull diagnostics via the HTTP bridge without needing adb.
 */
object ConversationLog {
    private const val MAX = 200
    private val ring = ConcurrentLinkedDeque<String>()

    fun log(tag: String, message: String) {
        Log.i(tag, message)
        val stamp = System.currentTimeMillis()
        ring.addLast("$stamp $tag $message")
        while (ring.size > MAX) ring.pollFirst()
    }

    fun error(tag: String, message: String) {
        Log.e(tag, message)
        val stamp = System.currentTimeMillis()
        ring.addLast("$stamp ERR $tag $message")
        while (ring.size > MAX) ring.pollFirst()
    }

    fun snapshot(): List<String> = ring.toList()
}
