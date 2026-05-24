package com.example.planna

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ScheduleEventBus {
    const val SESSION_EXPIRED_PREFIX = "[SESSION_EXPIRED]"

    private val _events = MutableSharedFlow<String>()
    val events = _events.asSharedFlow()

    suspend fun notify(message: String) {
        _events.emit(message)
    }

    suspend fun notifySessionExpired() {
        _events.emit("$SESSION_EXPIRED_PREFIX 다시 로그인이 필요합니다")
    }
}
