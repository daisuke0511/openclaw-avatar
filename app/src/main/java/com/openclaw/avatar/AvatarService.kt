package com.openclaw.avatar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground Service that owns the overlay WindowManager and AvatarView.
 * Handles drag-to-move and screen boundary clamping.
 */
class AvatarService : Service() {

    companion object {
        const val CHANNEL_ID = "openclaw_avatar_channel"
        const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private var avatarView: AvatarView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // Drag state
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartParamX = 0
    private var dragStartParamY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (avatarView == null) {
            addAvatarOverlay()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeAvatarOverlay()
        super.onDestroy()
    }

    // -- Foreground notification --

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpenClaw Avatar",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Avatar running"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startAsForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenClaw")
            .setContentText("Avatar active — tap to manage")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // -- Overlay management --

    private fun addAvatarOverlay() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)

        val avatarWidthPx  = (90 * dm.density).toInt()
        val avatarHeightPx = (108 * dm.density).toInt()

        val params = WindowManager.LayoutParams(
            avatarWidthPx,
            avatarHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - avatarWidthPx - (8 * dm.density).toInt()
            y = (200 * dm.density).toInt()
        }

        val view = AvatarView(this)
        view.setOnTouchListener(buildDragListener(params, dm))

        try {
            windowManager.addView(view, params)
            avatarView = view
            layoutParams = params
        } catch (e: Exception) {
            // Overlay permission may have been revoked; fail silently
        }
    }

    private fun removeAvatarOverlay() {
        avatarView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {}
            avatarView = null
            layoutParams = null
        }
    }

    // -- Drag handler --

    private fun buildDragListener(
        params: WindowManager.LayoutParams,
        dm: DisplayMetrics
    ): View.OnTouchListener {
        return View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX  = event.rawX
                    dragStartRawY  = event.rawY
                    dragStartParamX = params.x
                    dragStartParamY = params.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragStartRawX).toInt()
                    val dy = (event.rawY - dragStartRawY).toInt()
                    val newX = dragStartParamX + dx
                    val newY = dragStartParamY + dy
                    updateWindowPosition(params, view, newX, newY, dm)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    // Final position already stored in params.x / params.y
                    true
                }

                else -> false
            }
        }
    }

    private fun updateWindowPosition(
        params: WindowManager.LayoutParams,
        view: View,
        newX: Int,
        newY: Int,
        dm: DisplayMetrics
    ) {
        // Clamp so avatar never goes fully off-screen
        // Leave a bit of top margin for status bar
        val statusBarHeight = (24 * dm.density).toInt()
        val minX = 0
        val maxX = dm.widthPixels  - view.width
        val minY = statusBarHeight
        val maxY = dm.heightPixels - view.height

        params.x = newX.coerceIn(minX, maxX)
        params.y = newY.coerceIn(minY, maxY)

        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }
}
