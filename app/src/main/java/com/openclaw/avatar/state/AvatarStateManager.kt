package com.openclaw.avatar.state

import com.openclaw.avatar.AvatarService
import com.openclaw.avatar.events.AvatarEvent
import com.openclaw.avatar.events.MoveDir
import com.openclaw.avatar.events.Priority
import com.openclaw.avatar.events.SemanticState
import java.util.concurrent.atomic.AtomicReference

class AvatarStateManager(private val svc: AvatarService) : NextStateProvider {

    data class Snapshot(
        val currentAvatarState: String,
        val previousAvatarState: String?,
        val currentSemantic: String?,
        val priority: String,
        val stateStartedAt: Long,
        val durationMs: Long?,
        val x: Int,
        val y: Int,
        val walkingDir: String?,
        val frameIndex: Int,
        val bridgePort: Int,
    )

    // Active override (main thread only)
    private var activeState: AvatarService.State? = null
    private var activePriority: Priority = Priority.RANDOM_IDLE
    private var activeSemantic: SemanticState? = null
    private var activeExpiresAt: Long = 0
    private var previousAvatarState: AvatarService.State? = null
    private var stateStartedAt: Long = System.currentTimeMillis()
    private var activeDurationMs: Long? = null

    // Debounce (per source+key)
    private var lastEventKey: String? = null
    private var lastEventAt: Long = 0

    private val snapshotRef = AtomicReference<Snapshot>()
    var bridgePort: Int = -1
        set(value) { field = value; updateSnapshot() }

    init { updateSnapshot() }

    fun snapshot(): Snapshot = snapshotRef.get() ?: Snapshot(
        currentAvatarState = "IDLE",
        previousAvatarState = null,
        currentSemantic = null,
        priority = Priority.RANDOM_IDLE.name,
        stateStartedAt = System.currentTimeMillis(),
        durationMs = null,
        x = 0, y = 0,
        walkingDir = null,
        frameIndex = 0,
        bridgePort = bridgePort,
    )

    fun onEvent(e: AvatarEvent) {
        // Main-thread only (EventBus dispatches via mainHandler.post)

        // Debounce identical events within 500ms
        val key = eventKey(e)
        val now = System.currentTimeMillis()
        if (key == lastEventKey && (now - lastEventAt) < 500L) return
        lastEventKey = key; lastEventAt = now

        when (e) {
            is AvatarEvent.Semantic -> handleSemantic(e.state, e.durationMs, e.priority)
            is AvatarEvent.Action   -> handleSemantic(e.action, null, e.priority)
            is AvatarEvent.Tap      -> handleSemantic(SemanticState.GREETING, null, e.priority)
            is AvatarEvent.Battery  -> handleBattery(e)
            is AvatarEvent.Network  -> handleNetwork(e)
            is AvatarEvent.Move     -> handleMove(e)
        }
        updateSnapshot()
    }

    override fun nextState(from: AvatarService.State): AvatarService.State? {
        val active = activeState ?: return null
        if (System.currentTimeMillis() >= activeExpiresAt) {
            // Override expired → clear, let IdleBrain (existing RNG) take over
            clearActive()
            updateSnapshot()
            return null
        }
        return active
    }

    // --- Handlers ---

    private fun handleSemantic(sem: SemanticState, durationMs: Long?, priority: Priority) {
        // Priority gate: only interrupt if incoming >= current
        if (priority.rank < activePriority.rank &&
            System.currentTimeMillis() < activeExpiresAt) return

        val mapped = SemanticToAvatarMapper.map(sem)
        previousAvatarState = svc.currentAvatarState
        activeState = mapped
        activeSemantic = sem
        activePriority = priority
        stateStartedAt = System.currentTimeMillis()
        activeDurationMs = durationMs
        activeExpiresAt = System.currentTimeMillis() + (durationMs ?: defaultDurationFor(mapped))
        svc.forceEnterState(mapped, durationMs)
    }

    private fun handleBattery(e: AvatarEvent.Battery) {
        val sem: SemanticState = when {
            e.charging          -> SemanticState.HAPPY
            e.level in 0..19    -> SemanticState.TIRED
            else                -> return  // ignore normal battery updates
        }
        handleSemantic(sem, null, Priority.SYSTEM_EVENT)
    }

    private fun handleNetwork(e: AvatarEvent.Network) {
        val sem = if (e.available) SemanticState.HAPPY else SemanticState.SURPRISED
        handleSemantic(sem, null, Priority.SYSTEM_EVENT)
    }

    private fun handleMove(e: AvatarEvent.Move) {
        val state = if (e.dir == MoveDir.LEFT) AvatarService.State.WALK_LEFT
                    else AvatarService.State.WALK_RIGHT
        if (Priority.OPENCLAW_ACTION.rank < activePriority.rank &&
            System.currentTimeMillis() < activeExpiresAt) return
        previousAvatarState = svc.currentAvatarState
        activeState = state
        activeSemantic = null
        activePriority = Priority.OPENCLAW_ACTION
        stateStartedAt = System.currentTimeMillis()
        activeDurationMs = e.durationMs
        activeExpiresAt = System.currentTimeMillis() + e.durationMs
        svc.forceEnterState(state, e.durationMs)
    }

    private fun clearActive() {
        previousAvatarState = svc.currentAvatarState
        activeState = null
        activeSemantic = null
        activePriority = Priority.RANDOM_IDLE
        activeDurationMs = null
    }

    private fun eventKey(e: AvatarEvent): String = when (e) {
        is AvatarEvent.Semantic -> "sem:${e.state}"
        is AvatarEvent.Action   -> "act:${e.action}"
        is AvatarEvent.Tap      -> "tap"
        is AvatarEvent.Battery  -> "bat:${e.charging}:${(e.level / 10) * 10}"
        is AvatarEvent.Network  -> "net:${e.available}"
        is AvatarEvent.Move     -> "mov:${e.dir}"
    }

    private fun defaultDurationFor(s: AvatarService.State): Long = when (s) {
        AvatarService.State.WAVE, AvatarService.State.HEART,
        AvatarService.State.PLEASE, AvatarService.State.SHH,
        AvatarService.State.SURPRISED   -> 2500L
        AvatarService.State.CRY         -> 3500L
        AvatarService.State.EXCITED     -> 2000L
        AvatarService.State.READING     -> 6000L
        AvatarService.State.SLEEP, AvatarService.State.YAWN -> 5000L
        else                            -> 3000L
    }

    private fun updateSnapshot() {
        val walkingDir = when (svc.currentAvatarState) {
            AvatarService.State.WALK_LEFT  -> "LEFT"
            AvatarService.State.WALK_RIGHT -> "RIGHT"
            else -> null
        }
        val remaining = if (activeState != null)
            (activeExpiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
        else null
        snapshotRef.set(Snapshot(
            currentAvatarState = svc.currentAvatarState.name,
            previousAvatarState = previousAvatarState?.name,
            currentSemantic = activeSemantic?.name,
            priority = activePriority.name,
            stateStartedAt = stateStartedAt,
            durationMs = remaining,
            x = svc.positionX,
            y = svc.positionY,
            walkingDir = walkingDir,
            frameIndex = svc.currentFrameIndex,
            bridgePort = bridgePort,
        ))
    }
}
