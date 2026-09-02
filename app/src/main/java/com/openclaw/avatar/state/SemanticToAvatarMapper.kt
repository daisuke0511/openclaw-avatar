package com.openclaw.avatar.state

import com.openclaw.avatar.AvatarService
import com.openclaw.avatar.events.SemanticState

/**
 * Single source of truth for SemanticState → AvatarService.State conversion.
 * Change one line here to swap a semantic expression to a different sprite.
 */
object SemanticToAvatarMapper {
    fun map(s: SemanticState): AvatarService.State = when (s) {
        SemanticState.THINKING,
        SemanticState.WORKING       -> AvatarService.State.READING
        SemanticState.LISTENING     -> AvatarService.State.SURPRISED
        SemanticState.SPEAKING,
        SemanticState.HAPPY         -> AvatarService.State.IDLE
        SemanticState.TASK_COMPLETED -> AvatarService.State.EXCITED
        SemanticState.ERROR         -> AvatarService.State.CRY
        SemanticState.GREETING      -> AvatarService.State.WAVE
        SemanticState.AFFECTION,
        SemanticState.THANK_YOU     -> AvatarService.State.HEART
        SemanticState.QUIET         -> AvatarService.State.SHH
        SemanticState.REQUEST       -> AvatarService.State.PLEASE
        SemanticState.TIRED         -> AvatarService.State.YAWN
        SemanticState.SLEEPING      -> AvatarService.State.SLEEP
        SemanticState.SURPRISED     -> AvatarService.State.SURPRISED
    }
}
