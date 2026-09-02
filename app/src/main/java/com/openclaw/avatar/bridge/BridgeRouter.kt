package com.openclaw.avatar.bridge

import com.openclaw.avatar.events.AvatarEvent
import com.openclaw.avatar.events.EventBus
import com.openclaw.avatar.events.MoveDir
import com.openclaw.avatar.events.Priority
import com.openclaw.avatar.events.SemanticState
import com.openclaw.avatar.state.AvatarStateManager
import org.json.JSONObject

class BridgeRouter(private val manager: AvatarStateManager) {

    fun handle(req: BridgeRequest): BridgeResponse = try {
        when {
            req.method == "POST" && req.path == "/avatar/state"  -> handleState(req.body)
            req.method == "POST" && req.path == "/avatar/action" -> handleAction(req.body)
            req.method == "POST" && req.path == "/avatar/move"   -> handleMove(req.body)
            req.method == "GET"  && req.path == "/avatar/status" -> handleStatus()
            else -> BridgeResponse(404, """{"error":"not found"}""")
        }
    } catch (e: Exception) {
        BridgeResponse(400, """{"error":"${(e.message ?: "bad request").replace("\"", "'")}"}""")
    }

    private fun handleState(body: String): BridgeResponse {
        val json = JSONObject(body)
        val stateName = json.getString("state")
        val duration = if (json.has("duration")) json.getLong("duration") else null
        val sem = SemanticState.valueOf(stateName.uppercase())
        EventBus.publish(AvatarEvent.Semantic(
            state = sem, durationMs = duration,
            priority = Priority.OPENCLAW_ACTION, source = "http"
        ))
        return BridgeResponse(200, """{"ok":true,"state":"${sem.name}"}""")
    }

    private fun handleAction(body: String): BridgeResponse {
        val json = JSONObject(body)
        val actionName = json.getString("action")
        val sem = SemanticState.valueOf(actionName.uppercase())
        EventBus.publish(AvatarEvent.Action(action = sem, source = "http"))
        return BridgeResponse(200, """{"ok":true,"action":"${sem.name}"}""")
    }

    private fun handleMove(body: String): BridgeResponse {
        val json = JSONObject(body)
        val dirName = json.getString("direction")
        val duration = json.optLong("duration", 3000L)
        val dir = MoveDir.valueOf(dirName.uppercase())
        EventBus.publish(AvatarEvent.Move(dir = dir, durationMs = duration, source = "http"))
        return BridgeResponse(200, """{"ok":true,"direction":"${dir.name}"}""")
    }

    private fun handleStatus(): BridgeResponse {
        val s = manager.snapshot()
        val json = JSONObject()
        json.put("avatarState", s.currentAvatarState)
        json.put("previousAvatarState", s.previousAvatarState ?: JSONObject.NULL)
        json.put("semanticState", s.currentSemantic ?: JSONObject.NULL)
        json.put("priority", s.priority)
        json.put("stateStartedAt", s.stateStartedAt)
        json.put("durationRemainingMs", s.durationMs ?: JSONObject.NULL)
        json.put("position", JSONObject().put("x", s.x).put("y", s.y))
        json.put("walkingDir", s.walkingDir ?: JSONObject.NULL)
        json.put("frameIndex", s.frameIndex)
        json.put("bridgePort", s.bridgePort)
        return BridgeResponse(200, json.toString())
    }
}
