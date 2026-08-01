package com.bastion.app.receiver

import com.bastion.app.logging.runCatchingObserved
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import com.bastion.app.data.AppLauncherIcon
import com.bastion.app.data.AppLauncherLabel
import com.bastion.app.utils.AppLauncherIconManager
import com.bastion.app.utils.SettingsManager

class LauncherEntryRepairReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        // 使用 goAsync() 避免主线程阻塞；升级后事件只触发一次，但 DataStore 读取
        // 仍可能慢，用 withTimeout 兜底防止 ANR。
        // scope 作为本次 onReceive 的局部变量，在 finally 中 finish 后取消，避免泄漏。
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val settings = withTimeout(200) {
                    SettingsManager(context).settingsFlow.first()
                }
                AppLauncherIconManager.repairLaunchEntryPointsAfterUpgrade(
                    context,
                    settings.appLauncherIcon,
                    settings.appLauncherLabel
                )
            } catch (error: Exception) {
                Log.w(TAG, "Failed to repair launcher entry points after package replace", error)
                runCatchingObserved {
                    AppLauncherIconManager.repairLaunchEntryPointsAfterUpgrade(
                        context,
                        AppLauncherIcon.MODERN,
                        AppLauncherLabel.MONICA_PASS
                    )
                }
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        private const val TAG = "LauncherEntryRepair"
    }
}
