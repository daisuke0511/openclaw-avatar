package com.openclaw.avatar.events

enum class Priority(val rank: Int) {
    EMERGENCY(100),
    USER_INTERACTION(80),
    OPENCLAW_ACTION(60),
    SYSTEM_EVENT(40),
    IDLE_ACTION(20),
    RANDOM_IDLE(10),
}

enum class SemanticState {
    THINKING, WORKING, LISTENING, SPEAKING, HAPPY,
    TASK_COMPLETED, ERROR, GREETING, AFFECTION, THANK_YOU,
    QUIET, REQUEST, TIRED, SLEEPING, SURPRISED,
}

enum class MoveDir { LEFT, RIGHT }

sealed class AvatarEvent {
    abstract val priority: Priority
    abstract val source: String

    data class Semantic(
        val state: SemanticState,
        val durationMs: Long?,
        override val priority: Priority,
        override val source: String,
    ) : AvatarEvent()

    data class Action(
        val action: SemanticState,
        override val source: String,
    ) : AvatarEvent() {
        override val priority = Priority.OPENCLAW_ACTION
    }

    data class Move(
        val dir: MoveDir,
        val durationMs: Long,
        override val source: String,
    ) : AvatarEvent() {
        override val priority = Priority.OPENCLAW_ACTION
    }

    data class Tap(override val source: String = "tap") : AvatarEvent() {
        override val priority = Priority.USER_INTERACTION
    }

    data class Battery(
        val charging: Boolean,
        val level: Int,
        override val source: String = "battery",
    ) : AvatarEvent() {
        override val priority = Priority.SYSTEM_EVENT
    }

    data class Network(
        val available: Boolean,
        override val source: String = "network",
    ) : AvatarEvent() {
        override val priority = Priority.SYSTEM_EVENT
    }
}
