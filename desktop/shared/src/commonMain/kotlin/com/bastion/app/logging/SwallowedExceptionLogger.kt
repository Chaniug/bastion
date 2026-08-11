package com.bastion.app.logging

import com.bastion.app.platform.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 全局默认 tag。 */
private const val DEFAULT_TAG = "Bastion"

/** 限频：每个 tag 在窗口内最多打 [RATE_MAX] 条，防日志风暴。 */
private const val RATE_WINDOW_MS = 60_000L
private const val RATE_MAX = 50

/** 每个 tag 的限频状态。 */
private val lastLogCount: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap()
private val windowStart: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap()

/**
 * 语义与 [kotlin.runCatching] 对齐：保留非局部返回。
 *
 * 与安卓版 [runCatchingObserved] 的区别：桌面端直接基于 [Logger] 记录，无 DEBUG 构建门控。
 * 被吞掉的异常统一用 [Logger.e] 可观测地记录，绝不静默丢弃。
 *
 * @param T 返回值类型
 * @param tag 自定义 tag；为 null 时回落到 [DEFAULT_TAG]
 * @param block 可能抛异常的代码块（lambda，支持非局部返回）
 * @return 与 [kotlin.runCatching] 完全一致的 [kotlin.Result]
 */
inline fun <T> runCatchingObserved(
    tag: String? = null,
    block: () -> T
): kotlin.Result<T> =
    kotlin.runCatching(block).onFailure { logSwallowed(tag, it) }

/** 记录一次被吞掉的异常。 */
fun logSwallowed(tag: String?, throwable: Throwable?) {
    val t = tag ?: DEFAULT_TAG
    if (!shouldLog(t)) return
    try {
        Logger.e(t, "Swallowed exception", throwable)
    } catch (_: Throwable) {
        // 日志自身绝不能抛异常拖垮调用方
    }
}

/** 基于滑动时间窗的每-tag 限频：窗口内超过 [RATE_MAX] 条则丢弃，直至下一个窗口。 */
private fun shouldLog(tag: String): Boolean {
    val now = System.currentTimeMillis()
    val start = windowStart.computeIfAbsent(tag) { AtomicLong(now) }
    val startVal = start.get()
    if (now - startVal > RATE_WINDOW_MS) {
        start.set(now)
        lastLogCount.put(tag, AtomicLong(0))
        return true
    }
    val count = lastLogCount.computeIfAbsent(tag) { AtomicLong(0) }
    return count.incrementAndGet() <= RATE_MAX
}
