package com.openclaw.avatar.events

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

object EventBus {
    private val handler = Handler(Looper.getMainLooper())
    private val subs = CopyOnWriteArrayList<(AvatarEvent) -> Unit>()

    fun subscribe(s: (AvatarEvent) -> Unit) { subs.add(s) }
    fun unsubscribe(s: (AvatarEvent) -> Unit) { subs.remove(s) }

    fun publish(e: AvatarEvent) {
        handler.post { subs.forEach { it(e) } }
    }
}
