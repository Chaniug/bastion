package com.bastion.app.security.lock

import android.content.Context
import com.bastion.app.BuildConfig
import com.bastion.app.security.SecurityManager

/**
 * Single decision point for Bastion main-process startup authentication.
 *
 * Allowed callers:
 * - MainActivity cold-start bootstrap
 * - MainActivity foreground restoration
 *
 * The developer setting `disablePasswordVerification` is intentionally scoped
 * to app startup because the setting text describes startup verification only.
 */
object MainAppLockPolicy {

    fun resolveAccessState(
        securityManager: SecurityManager,
        context: Context,
        autoLockMinutes: Int,
        disablePasswordVerification: Boolean
    ): MainAppAccessState {
        val firstTime = !securityManager.isMasterPasswordSet()
        if (firstTime) {
            return MainAppAccessState(
                isFirstTime = true,
                bypassEnabled = false,
                canRestoreSession = false,
                reason = "first_time_setup_required"
            )
        }

        if (disablePasswordVerification && BuildConfig.DEBUG) {
            return MainAppAccessState(
                isFirstTime = false,
                bypassEnabled = true,
                canRestoreSession = false,
                reason = "startup_password_verification_disabled"
            )
        }

        val canRestoreSession = securityManager.canRestoreMainAppSession(context, autoLockMinutes)
        return MainAppAccessState(
            isFirstTime = false,
            bypassEnabled = false,
            canRestoreSession = canRestoreSession,
            reason = if (canRestoreSession) "restorable_session" else "authentication_required"
        )
    }
}
