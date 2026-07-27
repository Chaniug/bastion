package com.bastion.app.autofill_ng

import android.content.ClipData
import android.content.Context
import android.os.Build
import android.util.Log
import com.bastion.app.data.ItemType
import com.bastion.app.data.PasswordDatabase
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.model.TotpData
import com.bastion.app.security.SecurityManager
import com.bastion.app.steam.core.SteamTotp
import com.bastion.app.steam.data.SteamAccountRepository
import com.bastion.app.steam.data.SteamDatabase
import com.bastion.app.util.TotpDataResolver
import com.bastion.app.util.TotpGenerator
import com.bastion.app.autofill_ng.service.AutofillOtpNotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 统一 OTP 自动复制 / 通知副作用。
 *
 * 历史回归根因：OTP 自动复制仅挂在 [AutofillPickerActivityV2]（仅非认证 / 手动填充路径才会
 * 进入），而真实场景中 vault 锁定时填充走的是认证 dataset —— 框架会直接启动
 * [AutofillCipherCallbackActivity]，由它 `completeCipherAutofill()` 构建并返回 dataset 完成
 * 填充，根本不会经过 Picker Activity，于是 OTP 复制副作用从未触发。
 *
 * 这里把 OTP 解析 + 复制逻辑抽成共享函数，由两条填充路径共同调用，避免再次只挂在某一条上。
 *
 * 所有关键节点均使用统一 tag [TAG] 输出日志，便于下次用户日志定位链路走到哪一步。
 */
private const val TAG = "BastionOtpCopy"

/**
 * 密码填充完成后执行 OTP 自动复制 / 通知。
 *
 * @param context 建议传入 ApplicationContext（剪贴板写入与 DataStore 读取都不依赖 Activity）。
 * @param password 被填充的密码条目（可能绑定 TOTP 或 Steam Guard）。
 * @param autofillHints 本次填充请求的 hint 列表；若本次本身就是 OTP 字段则跳过。
 */
suspend fun performOtpAutofillSideEffects(
    context: Context,
    password: PasswordEntry,
    autofillHints: List<String>?,
) {
    val isOtpTarget = autofillHints
        ?.map { it.trim().lowercase() }
        ?.any(::isOtpHint) == true
    if (isOtpTarget) {
        Log.d(TAG, "skip: fill request is itself an OTP target, passwordId=${password.id}")
        return
    }

    runCatching {
        val preferences = AutofillPreferences(context)
        val showNotification = withContext(Dispatchers.IO) {
            preferences.isOtpNotificationEnabled.first()
        }
        val autoCopy = withContext(Dispatchers.IO) {
            preferences.isAutoCopyOtpEnabled.first()
        }
        Log.d(
            TAG,
            "prefs: autoCopy=$autoCopy, showNotification=$showNotification, passwordId=${password.id}, title=${password.title}"
        )
        if (!showNotification && !autoCopy) {
            Log.d(TAG, "skip: both autoCopy and showNotification disabled, passwordId=${password.id}")
            return
        }

        // Steam Guard 快捷通道：独立于常规 TOTP 存储，resolveOtpDataForPassword 查不到。
        val steamCode = resolveSteamGuardCodeForPassword(context, password)
        if (steamCode != null) {
            if (autoCopy) {
                writeClipboard(context, steamCode)
                Log.i(TAG, "copied Steam Guard code (len=${steamCode.length}), passwordId=${password.id}")
            }
            if (showNotification) {
                Log.d(TAG, "Steam Guard: live notification refresh not supported, skipped")
            }
            return
        }

        val totpData = resolveOtpDataForPassword(context, password)
        if (totpData == null) {
            Log.w(TAG, "no TOTP resolved (no authenticator key and no bound validator), passwordId=${password.id}")
            return
        }
        Log.d(
            TAG,
            "resolved TOTP: otpType=${totpData.otpType}, secretLen=${totpData.secret.length}, " +
                "boundPasswordId=${totpData.boundPasswordId}, passwordId=${password.id}"
        )
        val resolvedTotpData = resolveTotpDataForGeneration(context, totpData)
        val code = TotpGenerator.generateOtp(resolvedTotpData)
        Log.d(TAG, "generated OTP (len=${code.length}), passwordId=${password.id}")
        if (autoCopy) {
            writeClipboard(context, code)
            Log.i(TAG, "copied OTP to clipboard (len=${code.length}), passwordId=${password.id}")
        }
        if (showNotification) {
            val durationSeconds = withContext(Dispatchers.IO) {
                preferences.otpNotificationDuration.first()
            }
            AutofillOtpNotificationService.start(
                context = context.applicationContext,
                totpData = resolvedTotpData,
                label = password.title,
                durationSeconds = durationSeconds
            )
            Log.i(TAG, "started OTP notification, passwordId=${password.id}")
        }
    }.onFailure { e ->
        Log.e(TAG, "OTP side-effect failed, passwordId=${password.id}", e)
    }
}

/** 供填充 OTP 字段时直接生成验证码（不复制）。 */
suspend fun generateOtpCodeForPassword(context: Context, password: PasswordEntry): String? {
    resolveSteamGuardCodeForPassword(context, password)?.let { return it }
    val totpData = resolveOtpDataForPassword(context, password)
    if (totpData == null) {
        Log.w(TAG, "generateOtpCodeForPassword: no TOTP resolved, passwordId=${password.id}")
        return null
    }
    return runCatching {
        val resolvedTotpData = resolveTotpDataForGeneration(context, totpData)
        val code = TotpGenerator.generateOtp(resolvedTotpData)
        Log.d(TAG, "generated OTP for fill (len=${code.length}), passwordId=${password.id}")
        code.takeIf { it.isNotBlank() }
    }.onFailure { e ->
        Log.e(TAG, "generateOtpCodeForPassword failed, passwordId=${password.id}", e)
    }.getOrNull()
}

fun isOtpHint(normalizedHint: String): Boolean {
    if (normalizedHint.isBlank()) return false
    return normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.OTP_CODE.name.lowercase() ||
        normalizedHint.contains("totp") ||
        normalizedHint.contains("otp") ||
        normalizedHint.contains("2fa") ||
        normalizedHint.contains("twofactor") ||
        normalizedHint.contains("two_factor") ||
        normalizedHint.contains("verification") ||
        normalizedHint.contains("验证码") ||
        normalizedHint.contains("驗證碼") ||
        normalizedHint.contains("一次性")
}

private suspend fun resolveOtpDataForPassword(context: Context, password: PasswordEntry): TotpData? {
    val passwordTotpData = password.authenticatorKey
        .trim()
        .takeIf { it.isNotBlank() }
        ?.let { parsePasswordAuthenticatorTotpData(context, it) }
    return resolveOtpFromExistingValidators(context, password, passwordTotpData) ?: passwordTotpData
}

/**
 * 解析 Steam Guard 验证码：Steam 共享密钥存储在独立的 steam_accounts 表，
 * 仅当密码条目与 Steam 相关且自身无可解析常规 TOTP 时作为兜底通道。
 */
private suspend fun resolveSteamGuardCodeForPassword(context: Context, password: PasswordEntry): String? {
    val isSteamEntry = password.appPackageName?.contains("steam", ignoreCase = true) ?: false
        || password.website?.contains("steam", ignoreCase = true) ?: false
        || password.appName?.contains("steam", ignoreCase = true) ?: false
        || password.title?.contains("steam", ignoreCase = true) ?: false
    if (!isSteamEntry) return null

    val hasResolvableTotp = try {
        resolveOtpDataForPassword(context, password) != null
    } catch (_: Throwable) {
        false
    }
    if (hasResolvableTotp) return null

    return runCatching {
        val securityManager = SecurityManager(context)
        val steamRepo = SteamAccountRepository(
            SteamDatabase.getDatabase(context).steamAccountDao(),
            securityManager
        )
        val accounts = steamRepo.getAccounts()
        if (accounts.isEmpty()) return@runCatching null
        val matched = accounts.firstOrNull { acct ->
            acct.accountName.equals(password.username, ignoreCase = true)
                || acct.displayName.equals(password.username, ignoreCase = true)
                || acct.accountName.equals(password.title, ignoreCase = true)
                || acct.displayName.equals(password.title, ignoreCase = true)
        } ?: accounts.firstOrNull { it.selected } ?: accounts.firstOrNull()
        matched?.let {
            SteamTotp.generateAuthCode(it.sharedSecret, System.currentTimeMillis() / 1000)
        }
    }.onFailure { e ->
        Log.e(TAG, "Failed to resolve Steam Guard code, passwordId=${password.id}", e)
    }.getOrNull()
}

private suspend fun resolveOtpFromExistingValidators(
    context: Context,
    password: PasswordEntry,
    passwordTotpData: TotpData?,
): TotpData? {
    val validatorTotpList = withContext(Dispatchers.IO) {
        val securityManager = SecurityManager(context)
        val dao = PasswordDatabase.getDatabase(context).secureItemDao()
        dao.getActiveItemsByTypeSync(ItemType.TOTP)
            .mapNotNull { item ->
                TotpDataResolver.parseStoredItemData(
                    itemData = item.itemData,
                    fallbackIssuer = item.title,
                    decryptIfNeeded = securityManager::decryptDataIfBastionCiphertext
                )
            }
    }

    if (validatorTotpList.isEmpty()) return null

    validatorTotpList.firstOrNull { it.boundPasswordId == password.id }?.let { return it }

    val identityKey = buildTotpIdentityKey(passwordTotpData)
    if (identityKey.isNotEmpty()) {
        validatorTotpList.firstOrNull { buildTotpIdentityKey(it) == identityKey }?.let { return it }
    }

    return null
}

private fun buildTotpIdentityKey(data: TotpData?): String {
    val normalized = data?.let { TotpDataResolver.normalizeTotpData(it) } ?: return ""
    val normalizedSecret = TotpDataResolver.normalizeBase32Secret(normalized.secret)
    return listOf(
        normalized.otpType.name,
        normalizedSecret,
        normalized.digits.toString(),
        normalized.period.toString(),
        normalized.algorithm.uppercase(),
        normalized.counter.toString()
    ).joinToString("|")
}

private fun resolveTotpDataForGeneration(context: Context, totpData: TotpData): TotpData {
    val securityManager = SecurityManager(context)
    val decryptResult = runCatching { securityManager.decryptData(totpData.secret) }
    val decryptedSecret = decryptResult.getOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    Log.d(
        TAG,
        "OTP secret resolve: otpType=${totpData.otpType}, rawLen=${totpData.secret.length}, " +
            "decryptSuccess=${decryptResult.isSuccess && !decryptedSecret.isNullOrEmpty()}, " +
            "resolvedLen=${decryptedSecret?.length ?: totpData.secret.length}"
    )
    return if (!decryptedSecret.isNullOrEmpty()) {
        totpData.copy(secret = decryptedSecret)
    } else {
        totpData
    }
}

private fun parsePasswordAuthenticatorTotpData(context: Context, authenticatorKey: String): TotpData? {
    val securityManager = SecurityManager(context)
    return TotpDataResolver.fromAuthenticatorKey(
        rawKey = runCatching {
            securityManager.decryptDataIfBastionCiphertext(authenticatorKey)
        }.getOrDefault(authenticatorKey)
    )
}

private suspend fun writeClipboard(context: Context, code: String) {
    val appContext = context.applicationContext
    withContext(Dispatchers.Main) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (clipboard == null) {
            Log.w(TAG, "clipboard service unavailable, cannot copy OTP")
            return@withContext
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("OTP Code", code))
    }
}
