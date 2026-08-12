package com.bastion.desktop.di

import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.bitwarden.repository.BitwardenRepositoryStore
import com.bastion.app.bitwarden.repository.SqlDelightBitwardenRepositoryStore
import com.bastion.app.bitwarden.sync.BitwardenSyncOrchestrator
import com.bastion.app.bitwarden.sync.NetworkGateResult
import com.bastion.app.bitwarden.sync.SyncBlockReason
import com.bastion.app.bitwarden.sync.SyncExecutionOutcome
import com.bastion.app.db.BastionDatabase
import com.bastion.app.db.BastionDatabaseBundle
import com.bastion.app.db.BastionDatabaseFactory
import com.bastion.app.platform.PathProvider
import com.bastion.app.security.DesktopCryptoManager
import com.bastion.desktop.platform.OneDriveBrowserAuth
import com.bastion.desktop.platform.OneDriveSessionManager
import com.bastion.desktop.platform.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手动依赖装配（不引入 DI 框架）。
 * Phase 3 起仓储存储层使用 SQLDelight 持久化实现（[SqlDelightBitwardenRepositoryStore]），
 * 替换原先的 [com.bastion.app.bitwarden.repository.InMemoryBitwardenRepositoryStore]。
 */
object AppContainer {

    val cryptoManager: DesktopCryptoManager by lazy {
        DesktopCryptoManager().apply {
            ensureAppMasterKey()
        }
    }

    private val dbBundle: BastionDatabaseBundle by lazy {
        BastionDatabaseFactory.create(PathProvider.resolve("bastion.db"))
    }

    val database: BastionDatabase by lazy { dbBundle.database }

    val repositoryStore: BitwardenRepositoryStore by lazy {
        SqlDelightBitwardenRepositoryStore(dbBundle.database, dbBundle.driver, cryptoManager)
    }

    val bitwardenRepository: BitwardenRepository by lazy {
        BitwardenRepository(
            store = repositoryStore,
            cryptoManager = cryptoManager
        )
    }

    val oneDriveAuth: OneDriveBrowserAuth by lazy {
        OneDriveBrowserAuth()
    }

    val oneDriveSessionManager: OneDriveSessionManager by lazy {
        OneDriveSessionManager(cryptoManager)
    }

    /**
     * 应用级协程作用域，生命周期与进程一致，供同步编排器派生请求协程。
     * 退出时由 [syncScheduler] 统一取消（进程退出即回收）。
     */
    private val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /**
     * Bitwarden 同步编排器（去重 / 节流 / 重试退避）。
     * 通过 lambda 接入 [bitwardenRepository]，使「自动同步」开关与网络门控实际生效。
     * 桌面端无移动网络概念，[checkNetwork] 直接放行（见 PC 端开发计划 §3.1）。
     */
    val syncOrchestrator: BitwardenSyncOrchestrator by lazy {
        BitwardenSyncOrchestrator(
            scope = appScope,
            isAutoSyncEnabled = { bitwardenRepository.isAutoSyncEnabled },
            checkNetwork = { NetworkGateResult.ALLOWED },
            isVaultUnlocked = { vaultId -> bitwardenRepository.isVaultUnlocked(vaultId) },
            executeSync = { vaultId, _ ->
                when (val result = bitwardenRepository.sync(vaultId)) {
                    is BitwardenRepository.SyncResult.Success -> SyncExecutionOutcome.Success(
                        appliedChangeCount = result.appliedChangeCount,
                        availableOfflineCount = result.availableOfflineCount,
                        conflictCount = result.conflictCount,
                        uploadFailedCount = result.uploadFailedCount,
                        skippedDueToLocalDirtyCount = result.skippedDueToLocalDirtyCount
                    )
                    is BitwardenRepository.SyncResult.Error ->
                        SyncExecutionOutcome.RetryableError(result.message)
                    is BitwardenRepository.SyncResult.EmptyVaultBlocked ->
                        SyncExecutionOutcome.Blocked(SyncBlockReason.AUTH_REQUIRED, result.reason)
                }
            }
        )
    }

    /** 后台周期同步调度器（替代安卓 WorkManager）。 */
    val syncScheduler: SyncScheduler by lazy {
        SyncScheduler(syncOrchestrator, bitwardenRepository)
    }
}
