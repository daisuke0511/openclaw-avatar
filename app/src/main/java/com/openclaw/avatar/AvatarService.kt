package com.openclaw.avatar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs
import kotlin.random.Random

class AvatarService : Service() {

    companion object {
        const val CHANNEL_ID = "openclaw_avatar_channel"
        const val NOTIFICATION_ID = 1001
        const val FRAME_MS = 33L  // ~30fps
        const val SPEED_DP = 2.5f
    }

    private lateinit var windowManager: WindowManager
    private var avatarView: AvatarView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var dm = DisplayMetrics()

    // Autonomous movement
    private val handler = Handler(Looper.getMainLooper())
    private var velX = 0f
    private var velY = 0f
    private var isDragging = false

    // Drag state
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartParamX = 0
    private var dragStartParamY = 0

    private val moveRunnable = object : Runnable {
        override fun run() {
            if (!isDragging) moveStep()
            handler.postDelayed(this, FRAME_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (avatarView == null) addAvatarOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeAvatarOverlay()
        super.onDestroy()
    }

    // -- Foreground notification --

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "OpenClaw Avatar", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Avatar running"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startAsForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenClaw")
            .setContentText("Avatar active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // -- Overlay --

    private fun addAvatarOverlay() {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)

        val speedPx = SPEED_DP * dm.density
        velX = if (Random.nextBoolean()) speedPx else -speedPx
        velY = if (Random.nextBoolean()) speedPx * 0.6f else -speedPx * 0.6f

        val w = (90 * dm.density).toInt()
        val h = (108 * dm.density).toInt()

        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels / 2 - w / 2
            y = dm.heightPixels / 2 - h / 2
        }

        val view = AvatarView(this)
        view.setOnTouchListener(buildDragListener(params))

        try {
            windowManager.addView(view, params)
            avatarView = view
            layoutParams = params
            handler.post(moveRunnable)
        } catch (_: Exception) {}
    }

    private fun removeAvatarOverlay() {
        avatarView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            avatarView = null
            layoutParams = null
        }
    }

    // -- Autonomous movement --

    private fun moveStep() {
        val params = layoutParams ?: return
        val view = avatarView ?: return

        val statusBarH = (24 * dm.density).toInt()
        val maxX = dm.widthPixels - view.width
        val maxY = dm.heightPixels - view.height

        var newX = params.x + velX.toInt()
        var newY = params.y + velY.toInt()

        if (newX <= 0) { newX = 0; velX = abs(velX) }
        if (newX >= maxX) { newX = maxX; velX = -abs(velX) }
        if (newY <= statusBarH) { newY = statusBarH; velY = abs(velY) }
        if (newY >= maxY) { newY = maxY; velY = -abs(velY) }

        params.x = newX
        params.y = newY

        // Flip character based on horizontal direction
        view.setFlipped(velX < 0)

        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
    }

    // -- Drag --

    private fun buildDragListener(params: WindowManager.LayoutParams): View.OnTouchListener {
        return View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = true
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartParamX = params.x
                    dragStartParamY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragStartRawX).toInt()
                    val dy = (event.rawY - dragStartRawY).toInt()
                    val statusBarH = (24 * dm.density).toInt()
                    params.x = (dragStartParamX + dx).coerceIn(0, dm.widthPixels - view.width)
                    params.y = (dragStartParamY + dy).coerceIn(statusBarH, dm.heightPixels - view.height)
                    try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    // Give a small random kick after release
                    val speedPx = SPEED_DP * dm.density
                    velX = if (velX >= 0) speedPx else -speedPx
                    velY = if (velY >= 0) speedPx * 0.6f else -speedPx * 0.6f
                    true
                }
                else -> false
            }
        }
    }
}
