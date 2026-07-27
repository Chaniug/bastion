package com.bastion.app.autofill_ng

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.bastion.app.data.PasswordEntry
import com.bastion.app.security.SecurityManager
import com.bastion.app.utils.SettingsManager

object AccountFillPolicy {
    fun resolveAccountIdentifier(
        entry: PasswordEntry,
        securityManager: SecurityManager
    ): String {
        return try {
            if (looksEncryptedPayload(entry.username)) {
                securityManager.decryptData(entry.username)
            } else {
                entry.username
            }
        } catch (_: Exception) {
            entry.username
        }
    }

    fun resolveAccountIdentifierForDisplay(entry: PasswordEntry): String {
        val candidate = entry.username.trim()
        if (candidate.isBlank() || looksEncryptedPayload(candidate)) {
            return ""
        }
        return candidate
    }

    fun shouldFillEmailWithAccount(context: Context): Boolean {
        return runCatching {
            runBlocking {
                SettingsManager(context).settingsFlow.first().separateUsernameAccountEnabled
            }
        }.getOrDefault(false)
    }

    private fun looksEncryptedPayload(value: String): Boolean {
        return value.startsWith("V2|") ||
            value.startsWith("MDK|") ||
            value.startsWith("C2|") ||
            (value.contains("==") && value.length > 20)
    }
}



