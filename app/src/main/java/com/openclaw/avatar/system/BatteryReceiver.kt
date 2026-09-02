package com.openclaw.avatar.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.openclaw.avatar.events.AvatarEvent
import com.openclaw.avatar.events.EventBus

class BatteryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val (charging, level) = when (action) {
            Intent.ACTION_POWER_CONNECTED -> true to readLevel(context)
            Intent.ACTION_POWER_DISCONNECTED -> false to readLevel(context)
            Intent.ACTION_BATTERY_CHANGED -> {
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val ch = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL
                val lvl = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val pct = if (scale > 0) (lvl * 100 / scale) else lvl
                ch to pct
            }
            else -> return
        }
        EventBus.publish(AvatarEvent.Battery(charging = charging, level = level))
    }

    private fun readLevel(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}
