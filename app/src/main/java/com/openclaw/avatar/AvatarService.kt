package com.openclaw.avatar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.ConnectivityManager
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
import com.openclaw.avatar.bridge.AvatarBridgeServer
import com.openclaw.avatar.bridge.BridgeRouter
import com.openclaw.avatar.events.AvatarEvent
import com.openclaw.avatar.events.EventBus
import com.openclaw.avatar.state.AvatarStateManager
import com.openclaw.avatar.state.NextStateProvider
import com.openclaw.avatar.system.BatteryReceiver
import com.openclaw.avatar.system.NetworkListener
import kotlin.math.abs
import kotlin.random.Random

class AvatarService : Service() {

    companion object {
        const val CHANNEL_ID = "openclaw_avatar_channel"
        const val NOTIFICATION_ID = 1001
        const val FRAME_MS = 200L   // animation frame interval (ms)
        const val TICK_MS  = 33L    // movement tick interval (~30fps)
        const val SPEED_DP = 2.2f
        const val TAP_SLOP_DP = 8f  // touch below this distance = tap
    }

    // --- State machine ---
    enum class State {
        IDLE, WALK_RIGHT, WALK_LEFT, SLEEP, JUMP, WAVE,
        HAPPY_SITTING, YAWN, TEDDY, SHH, SHH_SITTING,
        PLEASE, CRY, HEART, SURPRISED, WAVE_SITTING, READING, EXCITED
    }

    private data class StateConfig(
        val frames: List<String>,
        val velX: Float,
        val velY: Float,
        val minDurMs: Long,
        val maxDurMs: Long
    )

    private val stateConfigs = mapOf(
        State.IDLE          to StateConfig(listOf("happy"),                                          0f, 0f, 2000, 5000),
        State.WALK_RIGHT    to StateConfig(listOf("walk_rt_0","walk_rt_1","walk_rt_2","walk_rt_3"),  1f, 0f, 4000, 10000),
        State.WALK_LEFT     to StateConfig(listOf("walk_lt_0","walk_lt_1","walk_lt_2","walk_lt_3"), -1f, 0f, 4000, 10000),
        State.SLEEP         to StateConfig(listOf("sleep_sitting"),                                  0f, 0f, 8000, 20000),
        State.JUMP          to StateConfig(listOf("excited"),                                        0f,-1f, 1200, 1200),
        State.WAVE          to StateConfig(listOf("wave"),                                           0f, 0f, 2000, 3000),
        State.HAPPY_SITTING to StateConfig(listOf("happy_sitting"),                                  0f, 0f, 2000, 4000),
        State.YAWN          to StateConfig(listOf("yawn_sitting"),                                   0f, 0f, 2000, 3000),
        State.TEDDY         to StateConfig(listOf("teddy_sitting"),                                  0f, 0f, 3000, 6000),
        State.SHH           to StateConfig(listOf("shh"),                                            0f, 0f, 2000, 3000),
        State.SHH_SITTING   to StateConfig(listOf("shh_sitting"),                                    0f, 0f, 2000, 3000),
        State.PLEASE        to StateConfig(listOf("please"),                                         0f, 0f, 2000, 3000),
        State.CRY           to StateConfig(listOf("cry"),                                            0f, 0f, 2000, 4000),
        State.HEART         to StateConfig(listOf("heart"),                                          0f, 0f, 2000, 3000),
        State.SURPRISED     to StateConfig(listOf("surprised"),                                      0f, 0f, 1500, 2000),
        State.WAVE_SITTING  to StateConfig(listOf("wave_sitting"),                                   0f, 0f, 2000, 3000),
        State.READING       to StateConfig(listOf("reading"),                                        0f, 0f, 4000, 8000),
        State.EXCITED       to StateConfig(listOf("excited"),                                        0f, 0f, 1500, 2500),
    )

    private val nextStates = mapOf(
        State.IDLE          to listOf(State.WALK_RIGHT, State.WALK_LEFT, State.SLEEP, State.WAVE, State.HAPPY_SITTING, State.YAWN, State.TEDDY, State.HEART, State.READING),
        State.WALK_RIGHT    to listOf(State.IDLE, State.WALK_LEFT, State.JUMP, State.WAVE, State.SURPRISED, State.SHH),
        State.WALK_LEFT     to listOf(State.IDLE, State.WALK_RIGHT, State.JUMP, State.WAVE, State.SURPRISED, State.SHH),
        State.SLEEP         to listOf(State.IDLE, State.YAWN),
        State.JUMP          to listOf(State.WALK_RIGHT, State.WALK_LEFT, State.IDLE, State.EXCITED),
        State.WAVE          to listOf(State.IDLE, State.WALK_RIGHT, State.WALK_LEFT, State.WAVE_SITTING),
        State.HAPPY_SITTING to listOf(State.IDLE, State.WALK_RIGHT, State.WALK_LEFT),
        State.YAWN          to listOf(State.SLEEP, State.IDLE),
        State.TEDDY         to listOf(State.SLEEP, State.IDLE, State.HAPPY_SITTING),
        State.SHH           to listOf(State.IDLE, State.SHH_SITTING),
        State.SHH_SITTING   to listOf(State.IDLE, State.WALK_RIGHT, State.WALK_LEFT),
        State.PLEASE        to listOf(State.IDLE, State.CRY, State.HAPPY_SITTING),
        State.CRY           to listOf(State.IDLE, State.PLEASE),
        State.HEART         to listOf(State.IDLE, State.WAVE, State.HAPPY_SITTING),
        State.SURPRISED     to listOf(State.IDLE, State.WALK_RIGHT, State.WALK_LEFT),
        State.WAVE_SITTING  to listOf(State.IDLE, State.WALK_RIGHT, State.WALK_LEFT),
        State.READING       to listOf(State.IDLE, State.SLEEP, State.YAWN),
        State.EXCITED       to listOf(State.IDLE, State.WALK_RIGHT, State.WALK_LEFT, State.WAVE),
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
    private var gestureMoved = false

    private val handler = Handler(Looper.getMainLooper())
    private var animTick = 0

    // --- Integration hooks (Phase 2/3/4/5/6) ---
    internal var nextStateProvider: NextStateProvider? = null
    private var stateManager: AvatarStateManager? = null
    private var bridgeServer: AvatarBridgeServer? = null
    private var batteryReceiver: BatteryReceiver? = null
    private var networkListener: NetworkListener? = null

    // Position accessor for state manager snapshots
    internal val positionX: Int get() = layoutParams?.x ?: 0
    internal val positionY: Int get() = layoutParams?.y ?: 0
    internal val currentAvatarState: State get() = currentState
    internal val currentFrameIndex: Int get() = frameIndex
    internal val currentStateEndTime: Long get() = stateEndTime

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
        val mgr = AvatarStateManager(this)
        stateManager = mgr
        nextStateProvider = mgr
        EventBus.subscribe(mgr::onEvent)

        val router = BridgeRouter(mgr)
        val server = AvatarBridgeServer(router::handle)
        val port = server.start()
        mgr.bridgePort = port
        bridgeServer = server

        // Battery events
        val br = BatteryReceiver()
        val bf = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        try {
            registerReceiver(br, bf)
            batteryReceiver = br
        } catch (_: Exception) {}

        // Network events
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            val nl = NetworkListener(cm)
            nl.start()
            networkListener = nl
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (avatarView == null) addOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        networkListener?.stop()
        networkListener = null
        batteryReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        batteryReceiver = null
        bridgeServer?.stop()
        bridgeServer = null
        stateManager?.let { EventBus.unsubscribe(it::onEvent) }
        stateManager = null
        nextStateProvider = null
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

    /** Externally-triggered state entry. Marshals to main thread. */
    fun forceEnterState(state: State, durationMs: Long?) {
        handler.post {
            currentState = state
            frameIndex = 0
            val cfg = stateConfigs[state]!!
            val dur = durationMs ?: (cfg.minDurMs + Random.nextLong(cfg.maxDurMs - cfg.minDurMs + 1))
            stateEndTime = System.currentTimeMillis() + dur
            avatarView?.showSprite(cfg.frames[0])
            if (state == State.JUMP) {
                jumpVelY = -(SPEED_DP * dm.density * 4f)
            }
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
        val override = nextStateProvider?.nextState(currentState)
        val next = override ?: nextStates[currentState]!!.let { it[Random.nextInt(it.size)] }
        enterState(next)
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

    private fun buildDragListener(params: WindowManager.LayoutParams): View.OnTouchListener {
        val slopPx = TAP_SLOP_DP * dm.density
        return View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = true
                    gestureMoved = false
                    dragStartRawX = event.rawX; dragStartRawY = event.rawY
                    dragStartParamX = params.x; dragStartParamY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val statusBarH = (24 * dm.density).toInt()
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY
                    if (!gestureMoved && (abs(dx) > slopPx || abs(dy) > slopPx)) {
                        gestureMoved = true
                    }
                    params.x = (dragStartParamX + dx.toInt())
                        .coerceIn(0, dm.widthPixels - view.width)
                    params.y = (dragStartParamY + dy.toInt())
                        .coerceIn(statusBarH, dm.heightPixels - view.height)
                    try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    isDragging = false
                    if (gestureMoved) {
                        // Preserve legacy behavior after a real drag
                        enterState(listOf(State.WAVE, State.JUMP, State.IDLE).random())
                    } else {
                        // Pure tap → semantic Tap event; StateManager maps → WAVE
                        EventBus.publish(AvatarEvent.Tap())
                    }
                    true
                }
                else -> false
            }
        }
    }
}
