package com.bastion.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive

private const val SMOOTH_TICK_MS = 50L
private const val TICK_STOP_TIMEOUT_MS = 5_000L

// 设计意图（架构升级 C.4.4，保留现状）：tickerScope 刻意作为文件级长生命周期 scope。
// 其下 smoothTicker/secondTicker 均用 SharingStarted.WhileSubscribed(5_000ms) 启动，
// 无任何订阅时会自动停止发射，因此该 scope 不会在无观察者时空转或泄漏，相对安全，予以保留。
private val tickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

private val smoothTicker = tickerFlow(smooth = true).stateIn(
    scope = tickerScope,
    started = SharingStarted.WhileSubscribed(TICK_STOP_TIMEOUT_MS),
    initialValue = System.currentTimeMillis()
)

private val secondTicker = tickerFlow(smooth = false).stateIn(
    scope = tickerScope,
    started = SharingStarted.WhileSubscribed(TICK_STOP_TIMEOUT_MS),
    initialValue = System.currentTimeMillis()
)

@Composable
fun rememberTotpTickerMillis(smooth: Boolean): Long {
    val millis by (if (smooth) smoothTicker else secondTicker).collectAsState()
    return millis
}

private fun tickerFlow(smooth: Boolean) = flow {
    while (currentCoroutineContext().isActive) {
        val now = System.currentTimeMillis()
        emit(now)
        val waitMillis = if (smooth) {
            SMOOTH_TICK_MS
        } else {
            (1000L - (now % 1000L)).coerceAtLeast(16L)
        }
        delay(waitMillis)
    }
}
