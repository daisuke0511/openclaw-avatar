package com.openclaw.avatar.state

/**
 * Phase 1 placeholder. When AvatarStateManager has no active override,
 * AvatarService.checkStateTransition falls back to its existing nextStates
 * random selection — that acts as the default IdleBrain.
 *
 * Future phases will add context-aware idle choice here (time of day,
 * battery level, recent events) without touching AvatarService.
 */
object IdleBrain
