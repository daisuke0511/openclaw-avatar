package com.openclaw.avatar.conversation

enum class ConversationState {
    OFF,
    CONNECTING,
    LISTENING,
    USER_SPEAKING,
    THINKING,
    TOOL_CALLING,
    AI_SPEAKING,
    INTERRUPTED,
    ERROR,
}
