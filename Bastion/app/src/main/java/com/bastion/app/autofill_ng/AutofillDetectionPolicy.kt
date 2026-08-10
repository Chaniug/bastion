package com.bastion.app.autofill_ng

import java.util.Locale
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.Accuracy
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint

internal object AutofillDetectionPolicy {
    private val usernameLabelTranslations = listOf(
        "nickname",
        "username",
        "utilisateur",
        "login",
        "логин",
        "логін",
        "користувач",
        "пользовател",
        "用户名",
        "用戶名",
        "id",
        "customer",
    )

    fun genericNumberFallbackAccuracy(): Accuracy = Accuracy.LOW

    fun shouldKeepTarget(
        hint: FieldHint,
        accuracy: Accuracy,
        hasPasswordTarget: Boolean,
        manualRequest: Boolean,
    ): Boolean {
        if (manualRequest) return true
        if (!isAccountHint(hint)) return true
        return accuracy.score >= Accuracy.MEDIUM.score || hasPasswordTarget
    }

    fun shouldIncludeHiddenCredential(
        hint: FieldHint,
        accuracy: Accuracy,
    ): Boolean {
        // 密码类是强登录信号，即便是低精度（如 VISIBLE_PASSWORD / NUMBER_PASSWORD
        // 变体映射为 LOW）且当前不可见，也应纳入解析，避免电影猎手这类 App 在聚焦
        // 账号框时因密码框尚未可见而被整体丢弃、导致密码填充失效。账号类仍需较高
        // 精度，避免把隐藏的搜索/备注等误判为登录账号（QQ 搜索框修复不受影响）。
        if (isPasswordHint(hint)) return accuracy.score >= Accuracy.LOWEST.score
        val credentialHint = isAccountHint(hint)
        return credentialHint && accuracy.score >= Accuracy.MEDIUM.score
    }

    /**
     * 服务层守卫（京东搜索栏误弹修复）：当屏幕上无密码框、且所有登录类字段均为
     * 弱信号（精度 < HIGH，即非系统标准 autofill hint）时，判定应抑制密码建议弹出。
     *
     * 这覆盖了弱解析兜底产生的误报：京东搜索栏被解析器以 LOWEST/MEDIUM 精度兜底为
     * USERNAME，无密码框配对，再经 allowPackageMatching 按包名匹配出京东密码条目。
     * 真实登录页有密码框（hasPasswordField=true），带标准系统 hint 的账号框精度为
     * HIGH，两者均不受影响。手动请求（用户主动长按）也不受此守卫限制。
     *
     * @param hasPasswordField fillableTargets 中是否存在密码类字段
     * @param loginFieldAccuracies fillableTargets 中所有登录类字段的精度列表
     * @param manualRequest 是否为手动请求（FLAG_MANUAL_REQUEST）
     * @return true 表示应跳过密码建议（不弹密码条目）
     */
    fun shouldSuppressWeakLoginSuggestion(
        hasPasswordField: Boolean,
        loginFieldAccuracies: List<Accuracy>,
        manualRequest: Boolean,
    ): Boolean {
        if (manualRequest) return false
        if (hasPasswordField) return false
        if (loginFieldAccuracies.isEmpty()) return false
        return loginFieldAccuracies.none { it.score >= Accuracy.HIGH.score }
    }

    fun matchesUsernameLabel(value: String): Boolean {
        val normalized = value.lowercase(Locale.ENGLISH).trim()
        if (normalized.isBlank()) return false
        return usernameLabelTranslations.any { translation ->
            if (translation == "id") {
                normalized
                    .split(Regex("[^\\p{L}\\p{N}]+"))
                    .any { token -> token == translation }
            } else {
                translation in normalized
            }
        }
    }

    fun matchesPhoneFieldName(value: String): Boolean {
        val normalized = value.lowercase(Locale.ENGLISH).trim()
        if (normalized.isBlank()) return false
        if (
            "phone" in normalized ||
            "mobile" in normalized ||
            "telephone" in normalized ||
            "手机号" in normalized ||
            "手機號" in normalized ||
            "电话号码" in normalized ||
            "電話號碼" in normalized
        ) {
            return true
        }
        return normalized
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .any { token -> token == "tel" }
    }

    private fun isAccountHint(hint: FieldHint): Boolean =
        hint == FieldHint.USERNAME ||
            hint == FieldHint.EMAIL_ADDRESS ||
            hint == FieldHint.PHONE_NUMBER

    private fun isPasswordHint(hint: FieldHint): Boolean =
        hint == FieldHint.PASSWORD || hint == FieldHint.NEW_PASSWORD
}
