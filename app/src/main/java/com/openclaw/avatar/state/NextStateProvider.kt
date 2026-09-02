package com.openclaw.avatar.state

import com.openclaw.avatar.AvatarService

interface NextStateProvider {
    fun nextState(from: AvatarService.State): AvatarService.State?
}
