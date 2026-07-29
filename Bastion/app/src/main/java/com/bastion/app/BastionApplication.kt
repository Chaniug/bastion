package com.bastion.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import com.bastion.app.attachments.AttachmentContainer
import com.bastion.app.data.AppLauncherIcon
import com.bastion.app.data.AppLauncherLabel
import com.bastion.app.data.PasswordDatabase
import com.bastion.app.mdbx.MdbxDiagLogger
import com.bastion.app.perf.MainThreadStallMonitor
import com.bastion.app.security.AppUpdateSecurityGuard
import com.bastion.app.sync.AndroidSyncNetworkGate
import com.bastion.app.sync.SyncTaskRunner
import com.bastion.app.utils.AppLauncherIconManager
import com.bastion.app.security.SessionManager
import com.bastion.app.utils.SettingsManager
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bastion.app.security.SecurityManager
import com.bastion.app.webdav.WebDavBackoffState
import com.bastion.app.workers.KeePassRemoteUploadWorker

/**
 * Bastion 应用程序入口
 * 
 * 负责初始化全局依赖注入容器（Koin）
 * 
 * 安全设计考量:
 * - Koin 在进程级别初始化，生命周期与应用一致
 * - 敏感依赖使用 single 作用域，避免多实例
 * - 模块化设计便于测试时替换 mock 实现
 */
class BastionApplication : Application() {
    
    companion object {
        private const val TAG = "BastionApplication"
    }
    
    override fun onCreate() {
        super.onCreate()

        // —— 所有进程（含独立进程 :accessibility）都需要的轻量初始化 ——
        SessionManager.attachAppContext(this)

        // 「重启后锁定」(-2)：内部已按进程守卫，子进程为 no-op
        SessionManager.enforceLockOnRestartIfNeeded(this)

        // 版本变更强制锁定：安全守卫，无版本变更时仅是一次轻量 SharedPreferences 读写
        AppUpdateSecurityGuard.enforceLockIfAppUpdated(
            context = this,
            reason = "application_on_create"
        )

        // Koin 启动：本仓库 startKoin 未注册任何 module，成本极低；
        // 全进程保留以兼容可能依赖 Koin 的组件（:accessibility 实际不使用，但保留无副作用）。
        initKoin()

        // —— 以下为主进程专属的「重度」初始化 ——
        // :accessibility 等独立进程常驻后台，不应承担这些与主业务相关的开销
        // （主线程卡顿监控、诊断日志、附件清理、启动器入口同步、WebDAV 退避持久化、
        // KeePass 上传恢复、同步网络门），以降低常驻进程内存与后台 CPU 占用。
        // 仅在「确定」是非主进程时才跳过；进程名判不明时回退到完整初始化，
        // 保证主进程逻辑绝不漏跑。
        if (SessionManager.isNonMainProcess(this)) {
            return
        }

        // 后台线程预热加密单例（SecurityManager），将 Keystore / EncryptedSharedPreferences
        // 的初始化从主线程移出（A1），避免冷启动与旋转屏幕时的主线程阻塞。
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            SecurityManager.prewarm(this@BastionApplication)
        }

        SyncTaskRunner.installNetworkGate(AndroidSyncNetworkGate(this))
        MainThreadStallMonitor.start()
        MdbxDiagLogger.initialize(this)
        syncLauncherEntryPointsWithSettings()
        WebDavBackoffState.attachPersistence(this)
        scheduleKeePassRemoteUploadRecovery()
        scheduleAttachmentHousekeeping()
    }
    
    /**
     * 初始化 Koin 依赖注入框架
     */
    private fun initKoin() {
        startKoin {
            // 关闭日志以提高性能和安全性
            androidLogger(Level.NONE)
            
            // 提供 Android Context
            androidContext(this@BastionApplication)
        }
    }

    private fun scheduleKeePassRemoteUploadRecovery() {
        runCatching {
            KeePassRemoteUploadWorker.enqueueIfPending(this)
        }.onFailure { error ->
            Log.w(TAG, "Failed to schedule KeePass remote upload recovery", error)
        }
    }

    /**
     * 附件子系统的启动级维护：
     * - 扫描并删除 Room 已不再引用的密文孤儿文件
     *
     * 在独立协程里跑，失败不影响应用启动。
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun scheduleAttachmentHousekeeping() {
        ProcessLifecycleOwner.get().lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val facade = AttachmentContainer.facade(this@BastionApplication)
                facade.purgeOrphanedLocalBlobs()
            }.onFailure { Log.w(TAG, "Attachment housekeeping failed", it) }
        }
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun syncLauncherEntryPointsWithSettings() {
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val settings = SettingsManager(this@BastionApplication).settingsFlow.first()
                AppLauncherIconManager.repairLaunchEntryPointsAfterUpgrade(
                    this@BastionApplication,
                    settings.appLauncherIcon,
                    settings.appLauncherLabel
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to sync launcher entry points with settings", error)
                runCatching {
                    AppLauncherIconManager.repairLaunchEntryPointsAfterUpgrade(
                        this@BastionApplication,
                        AppLauncherIcon.MODERN,
                        AppLauncherLabel.MONICA_PASS
                    )
                }.onFailure { fallbackError ->
                    Log.w(TAG, "Failed to apply fallback launcher entry points", fallbackError)
                }
            }
        }
    }

}

