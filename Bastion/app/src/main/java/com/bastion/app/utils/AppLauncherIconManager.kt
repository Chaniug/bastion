package com.bastion.app.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.bastion.app.MainActivity
import com.bastion.app.R
import com.bastion.app.data.AppLauncherIcon
import com.bastion.app.data.AppLauncherLabel

object AppLauncherIconManager {
    private const val COMPAT_MODERN_ALIAS = "com.bastion.app.ModernLauncherAlias"
    private const val COMPAT_CLASSIC_ALIAS = "com.bastion.app.LockLauncherAlias"
    private const val HOME_MODERN_ALIAS = "com.bastion.app.ModernHomeLauncherAlias"
    private const val HOME_CLASSIC_ALIAS = "com.bastion.app.ClassicHomeLauncherAlias"
    private const val VISIBLE_MODERN_PASS_ALIAS = "com.bastion.app.ModernVisibleLauncherAlias"
    private const val VISIBLE_CLASSIC_PASS_ALIAS = "com.bastion.app.ClassicVisibleLauncherAlias"
    private const val VISIBLE_MODERN_MONICA_ALIAS = "com.bastion.app.ModernVisibleLauncherAliasBastion"
    private const val VISIBLE_CLASSIC_MONICA_ALIAS = "com.bastion.app.ClassicVisibleLauncherAliasBastion"

    fun apply(context: Context, icon: AppLauncherIcon, label: AppLauncherLabel) {
        repairCompatibilityLaunchTargets(context)
        applyVisibleLauncherSelection(context, label)
    }

    fun repairLegacyDisabledComponents(context: Context) {
        repairCompatibilityLaunchTargets(context)
    }

    fun repairLaunchEntryPointsAfterUpgrade(
        context: Context,
        icon: AppLauncherIcon,
        label: AppLauncherLabel
    ) {
        repairCompatibilityLaunchTargets(context)
        applyVisibleLauncherSelection(context, label)
    }

    fun getCurrentSelection(context: Context): AppLauncherIcon {
        return AppLauncherIcon.MODERN
    }

    fun resolveBrandingIconRes(context: Context): Int {
        // 与桌面启动器图标保持一致：使用当前启用的 launcher mipmap。
        // 此前返回 R.drawable.bastion_launcher（drawable-nodpi/bastion_launcher.png），
        // 该 png 是未随品牌更新换掉的旧图标，导致通知/生物识别等位置仍显示旧图标。
        return when (getCurrentSelection(context)) {
            AppLauncherIcon.MODERN -> R.mipmap.ic_launcher_modern
        }
    }

    fun applyBiometricPromptBranding(context: Context, promptInfoBuilder: Any) {
        val builderClass = promptInfoBuilder.javaClass
        val iconRes = resolveBrandingIconRes(context)

        runCatching {
            builderClass.methods.firstOrNull { method ->
                method.name == "setLogoRes" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            }?.invoke(promptInfoBuilder, iconRes)
        }

        runCatching {
            builderClass.methods.firstOrNull { method ->
                method.name == "setLogoDescription" &&
                    method.parameterTypes.size == 1 &&
                    CharSequence::class.java.isAssignableFrom(method.parameterTypes[0])
            }?.invoke(promptInfoBuilder, context.getString(R.string.app_name))
        }
    }

    private fun repairCompatibilityLaunchTargets(context: Context) {
        val packageManager = context.packageManager
        val components = listOf(
            ComponentName(context, MainActivity::class.java),
            ComponentName(context, COMPAT_MODERN_ALIAS),
            ComponentName(context, COMPAT_CLASSIC_ALIAS),
            ComponentName(context, HOME_MODERN_ALIAS),
            ComponentName(context, HOME_CLASSIC_ALIAS)
        )

        components.forEach { component ->
            packageManager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun applyVisibleLauncherSelection(
        context: Context,
        label: AppLauncherLabel
    ) {
        val packageManager = context.packageManager
        val states = mapOf(
            ComponentName(context, VISIBLE_MODERN_PASS_ALIAS) to componentStateFor(
                label == AppLauncherLabel.MONICA_PASS
            ),
            ComponentName(context, VISIBLE_CLASSIC_PASS_ALIAS) to
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            ComponentName(context, VISIBLE_MODERN_MONICA_ALIAS) to componentStateFor(
                label == AppLauncherLabel.MONICA
            ),
            ComponentName(context, VISIBLE_CLASSIC_MONICA_ALIAS) to
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(
                states.map { (component, state) ->
                    PackageManager.ComponentEnabledSetting(
                        component,
                        state,
                        PackageManager.DONT_KILL_APP
                    )
                }
            )
            return
        }

        states.forEach { (component, state) ->
            packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun componentStateFor(shouldEnable: Boolean): Int {
        return if (shouldEnable) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
    }
}
