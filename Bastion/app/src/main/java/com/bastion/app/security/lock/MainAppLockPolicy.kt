package com.bastion.app.security.lock

import android.content.Context
import com.bastion.app.security.SecurityManager

/**
 * Single decision point for Bastion main-process startup authentication.
 *
 * Allowed callers:
 * - MainActivity cold-start bootstrap
 * - MainActivity foreground restoration
 */
object MainAppLockPolicy {

    fun resolveAccessState(
        securityManager: SecurityManager,
        context: Context,
        autoLockMinutes: Int
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

        val canRestoreSession = securityManager.canRestoreMainAppSession(context, autoLockMinutes)
        return MainAppAccessState(
            isFirstTime = false,
            bypassEnabled = false,
            canRestoreSession = canRestoreSession,
            reason = if (canRestoreSession) "restorable_session" else "authentication_required"
        )
    }
}
