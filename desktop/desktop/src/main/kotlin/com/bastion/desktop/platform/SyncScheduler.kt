package com.bastion.desktop.platform

import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.bitwarden.sync.BitwardenSyncOrchestrator
import com.bastion.app.bitwarden.sync.SyncTriggerReason
import com.bastion.app.platform.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 桌面端后台周期同步调度器（替代安卓 WorkManager）。
 *
 * 职责（只负责「何时发起」，去重/节流/重试退避交由 [BitwardenSyncOrchestrator]）：
 * - [start] 后进入循环：当「自动同步」开启时，对所有「已解锁」vault 周期性触发 [SyncTriggerReason.PERIODIC] 同步；
 *   启动时额外触发一次 [SyncTriggerReason.APP_RESUME] 即时同步。
 * - [requestSyncNow] 供手动「立即同步」事件驱动（目前 UI 直接调 repository.sync，本方法作为统一入口保留）。
 *
 * @param intervalMs 周期同步间隔，默认 15 分钟。
 */
class SyncScheduler(
    private val orchestrator: BitwardenSyncOrchestrator,
    private val repository: BitwardenRepository,
    private val intervalMs: Long = 15 * 60 * 1000L
) {
    private val TAG = "SyncScheduler"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    fun start() {
        if (loopJob != null) {
            Logger.w(TAG, "Scheduler already started, ignoring duplicate start()")
            return
        }
        loopJob = scope.launch {
            // 启动即时同步（应用进入前台语义）
            triggerForUnlockedVaults(SyncTriggerReason.APP_RESUME)
            while (isActive) {
                delay(intervalMs)
                if (repository.isAutoSyncEnabled) {
                    triggerForUnlockedVaults(SyncTriggerReason.PERIODIC)
                } else {
                    Logger.d(TAG, "Auto sync disabled, skipping periodic cycle")
                }
            }
        }
        Logger.i(TAG, "SyncScheduler started (interval=${intervalMs}ms, autoSync=${repository.isAutoSyncEnabled})")
    }

    /** 手动触发某 vault 同步（经编排器，享受去重/节流/重试）。 */
    fun requestSyncNow(vaultId: Long) {
        orchestrator.requestSync(vaultId, SyncTriggerReason.MANUAL)
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        Logger.i(TAG, "SyncScheduler stopped")
    }

    private suspend fun triggerForUnlockedVaults(reason: SyncTriggerReason) {
        runCatching {
            val vaults = repository.getAllVaults()
            vaults.forEach { vault ->
                if (repository.isVaultUnlocked(vault.id)) {
                    orchestrator.requestSync(vault.id, reason)
                }
            }
        }.onFailure { e ->
            Logger.e(TAG, "Failed to enumerate vaults for periodic sync", e)
        }
    }
}
