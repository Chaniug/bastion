package com.bastion.app.receiver

import com.bastion.app.logging.runCatchingObserved
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.bastion.app.data.AppLauncherIcon
import com.bastion.app.data.AppLauncherLabel
import com.bastion.app.utils.AppLauncherIconManager
import com.bastion.app.utils.SettingsManager

class LauncherEntryRepairReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        runCatchingObserved {
            val settings = runBlocking {
                SettingsManager(context).settingsFlow.first()
            }
            AppLauncherIconManager.repairLaunchEntryPointsAfterUpgrade(
                context,
                settings.appLauncherIcon,
                settings.appLauncherLabel
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to repair launcher entry points after package replace", error)
            runCatchingObserved {
                AppLauncherIconManager.repairLaunchEntryPointsAfterUpgrade(
                    context,
                    AppLauncherIcon.MODERN,
                    AppLauncherLabel.MONICA_PASS
                )
            }
        }
    }

    companion object {
        private const val TAG = "LauncherEntryRepair"
    }
}
