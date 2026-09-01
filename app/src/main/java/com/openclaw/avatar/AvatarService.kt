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
        const val FRAME_MS = 200L   // animation frame interval (ms)
        const val TICK_MS  = 33L    // movement tick interval (~30fps)
        const val SPEED_DP = 2.2f
    }

    // --- State machine ---
    enum class State { IDLE, WALK_RIGHT, WALK_LEFT, SLEEP, JUMP, WAVE }

    private data class StateConfig(
        val frames: List<String>,
        val velX: Float,
        val velY: Float,
        val minDurMs: Long,
        val maxDurMs: Long
    )

    private val stateConfigs = mapOf(
        State.IDLE       to StateConfig(listOf("idle"),                       0f, 0f, 2000, 6000),
        State.WALK_RIGHT to StateConfig(listOf("walk_rt_0","walk_rt_1","walk_rt_2"), 1f, 0f, 4000, 10000),
        State.WALK_LEFT  to StateConfig(listOf("walk_lt_0","walk_lt_1","walk_lt_2"),-1f, 0f, 4000, 10000),
        State.SLEEP      to StateConfig(listOf("sleep_0","sleep_1"),          0f, 0f, 8000, 20000),
        State.JUMP       to StateConfig(listOf("jump"),                       0f,-1f, 1200, 1200),
        State.WAVE       to StateConfig(listOf("wave"),                       0f, 0f, 2000, 3000),
    )

    private val nextStates = mapOf(
        State.IDLE       to listOf(State.WALK_RIGHT, State.WALK_LEFT, State.SLEEP, State.WAVE),
        State.WALK_RIGHT to listOf(State.IDLE, State.WALK_LEFT, State.JUMP, State.WAVE),
        State.WALK_LEFT  to listOf(State.IDLE, State.WALK_RIGHT, State.JUMP, State.WAVE),
        State.SLEEP      to listOf(State.IDLE),
        State.JUMP       to listOf(State.WALK_RIGHT, State.WALK_LEFT, State.IDLE),
        State.WAVE       to listOf(State.IDLE, State.WALK_RIGHT, State.WALK_LEFT),
    )

    private var currentState = State.IDLE
    private var stateEndTime = 0L
    private var frameIndex = 0
    private var jumpVelY = 0f
    private var isDragging = false

    private lateinit var windowManager: WindowManager
    private var avatarView: AvatarView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val dm = DisplayMetrics()

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartParamX = 0
    private var dragStartParamY = 0

    private val handler = Handler(Looper.getMainLooper())
    private var animTick = 0

    private val mainRunnable = object : Runnable {
        override fun run() {
            if (!isDragging) {
                moveTick()
                animTick++
                if (animTick * TICK_MS >= FRAME_MS) {
                    animTick = 0
                    advanceFrame()
                }
                checkStateTransition()
            }
            handler.postDelayed(this, TICK_MS)
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
        if (avatarView == null) addOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        super.onDestroy()
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "OpenClaw Avatar", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun startAsForeground() {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenClaw")
            .setContentText("Avatar active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi).setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, n)
    }

    // --- Overlay ---

    private fun addOverlay() {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        val w = (90 * dm.density).toInt()
        val h = (108 * dm.density).toInt()
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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
            enterState(State.IDLE)
            handler.post(mainRunnable)
        } catch (_: Exception) {}
    }

    private fun removeOverlay() {
        avatarView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        avatarView = null; layoutParams = null
    }

    // --- State machine ---

    private fun enterState(state: State) {
        currentState = state
        frameIndex = 0
        val cfg = stateConfigs[state]!!
        val dur = cfg.minDurMs + Random.nextLong(cfg.maxDurMs - cfg.minDurMs + 1)
        stateEndTime = System.currentTimeMillis() + dur
        avatarView?.showSprite(cfg.frames[0])
        if (state == State.JUMP) {
            jumpVelY = -(SPEED_DP * dm.density * 4f)
        }
    }

    private fun advanceFrame() {
        val frames = stateConfigs[currentState]!!.frames
        if (frames.size > 1) {
            frameIndex = (frameIndex + 1) % frames.size
            avatarView?.showSprite(frames[frameIndex])
        }
    }

    private fun checkStateTransition() {
        if (System.currentTimeMillis() < stateEndTime) return
        val candidates = nextStates[currentState]!!
        enterState(candidates[Random.nextInt(candidates.size)])
    }

    // --- Movement ---

    private fun moveTick() {
        val params = layoutParams ?: return
        val view = avatarView ?: return
        val cfg = stateConfigs[currentState]!!
        val speedPx = SPEED_DP * dm.density
        val statusBarH = (24 * dm.density).toInt()
        val maxX = dm.widthPixels - view.width
        val maxY = dm.heightPixels - view.height

        if (currentState == State.JUMP) {
            jumpVelY += speedPx * 0.25f  // gravity
            params.y = (params.y + jumpVelY.toInt()).coerceIn(statusBarH, maxY)
            if (params.y >= maxY) {
                stateEndTime = 0  // force state transition
            }
        } else if (cfg.velX != 0f) {
            val newX = params.x + (cfg.velX * speedPx).toInt()
            if (newX <= 0) {
                params.x = 0
                enterState(State.WALK_RIGHT)
                return
            } else if (newX >= maxX) {
                params.x = maxX
                enterState(State.WALK_LEFT)
                return
            } else {
                params.x = newX
            }
        }

        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
    }

    // --- Drag ---

    private fun buildDragListener(params: WindowManager.LayoutParams): View.OnTouchListener =
        View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = true
                    dragStartRawX = event.rawX; dragStartRawY = event.rawY
                    dragStartParamX = params.x; dragStartParamY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val statusBarH = (24 * dm.density).toInt()
                    params.x = (dragStartParamX + (event.rawX - dragStartRawX).toInt())
                        .coerceIn(0, dm.widthPixels - view.width)
                    params.y = (dragStartParamY + (event.rawY - dragStartRawY).toInt())
                        .coerceIn(statusBarH, dm.heightPixels - view.height)
                    try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    enterState(listOf(State.WAVE, State.JUMP, State.IDLE).random())
                    true
                }
                else -> false
            }
        }
}
