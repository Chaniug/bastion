package com.bastion.app.autofill_ng

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.Accuracy

/**
 * 回归测试：京东搜索栏误弹密码修复（服务层守卫）。
 *
 * 根因：京东搜索栏被弱解析兜底为 USERNAME（LOWEST/MEDIUM），无密码框配对，
 * 再经 allowPackageMatching 按包名匹配出京东密码条目弹出。
 *
 * 守卫策略：无密码框 + 所有登录类字段精度 < HIGH（非系统标准 autofill hint）
 * 时不弹密码条目。真实登录页（有密码框）与带标准系统 hint 的账号框不受影响。
 */
class WeakLoginGuardTest {

    // --- 应抑制（不弹密码）的场景 ---

    @Test
    fun jdSearchBar_singleLowestUsername_noPassword_isSuppressed() {
        // 京东搜索栏被 WebView WEB_EDIT_TEXT 兜底为 USERNAME:LOWEST
        assertTrue(
            AutofillDetectionPolicy.shouldSuppressWeakLoginSuggestion(
                hasPasswordField = false,
                loginFieldAccuracies = listOf(Accuracy.LOWEST),
                manualRequest = false,
            )
        )
    }

    @Test
    fun jdSearchBar_singleMediumUsername_noPassword_isSuppressed() {
        // native id 含 "username" 术语 → MEDIUM 精度，但无密码框仍应抑制
        assertTrue(
            AutofillDetectionPolicy.shouldSuppressWeakLoginSuggestion(
                hasPasswordField = false,
                loginFieldAccuracies = listOf(Accuracy.MEDIUM),
                manualRequest = false,
            )
        )
    }

    @Test
    fun multipleWeakLoginFields_noPassword_isSuppressed() {
        // 多个弱信号登录字段（LOW + MEDIUM）无密码框 → 抑制
        assertTrue(
            AutofillDetectionPolicy.shouldSuppressWeakLoginSuggestion(
                hasPasswordField = false,
                loginFieldAccuracies = listOf(Accuracy.LOW, Accuracy.MEDIUM),
                manualRequest = false,
            )
        )
    }

    @Test
    fun emptyLoginFields_noPassword_isNotSuppressed() {
        // 无登录类字段时不触发此守卫（由 structuredDecision 等其他逻辑处理）
        assertFalse(
            AutofillDetectionPolicy.shouldSuppressWeakLoginSuggestion(
                hasPasswordField = false,
                loginFieldAccuracies = emptyList(),
                manualRequest = false,
            )
        )
    }

    // --- 不应抑制（正常弹密码）的场景 ---

    @Test
    fun realLoginPage_passwordFieldPresent_isNotSuppressed() {
        // 真实登录页有密码框 → 守卫不触发，正常弹密码
        assertFalse(
            AutofillDetectionPolicy.shouldSuppressWeakLoginSuggestion(
                hasPasswordField = true,
                loginFieldAccuracies = listOf(Accuracy.LOWEST),
                manualRequest = false,
            )
        )
    }

    @Test
    fun systemHintUsername_highAccuracy_noPassword_isNotSuppressed() {
        // 带标准系统 autofill hint 的账号框精度为 HIGH → 守卫不触发
        assertFalse(
            AutofillDetectionPolicy.shouldSuppressWeakLoginSuggestion(
                hasPasswordField = false,
                loginFieldAccuracies = listOf(Accuracy.HIGH),
                manualRequest = false,
            )
        )
    }

    @Test
    fun mixedAccuracyWithHigh_noPassword_isNotSuppressed() {
        // 混合精度中至少一个 >= HIGH → 守卫不触发
        assertFalse(
            AutofillDetectionPolicy.shouldSuppressWeakLoginSuggestion(
                hasPasswordField = false,
                loginFieldAccuracies = listOf(Accuracy.LOWEST, Accuracy.HIGH),
                manualRequest = false,
            )
        )
    }

    @Test
    fun manualRequest_lowestUsername_noPassword_isNotSuppressed() {
        // 手动请求（用户主动长按）不受此守卫限制
        assertFalse(
            AutofillDetectionPolicy.shouldSuppressWeakLoginSuggestion(
                hasPasswordField = false,
                loginFieldAccuracies = listOf(Accuracy.LOWEST),
                manualRequest = true,
            )
        )
    }

    @Test
    fun highestAccuracy_noPassword_isNotSuppressed() {
        // HIGHEST 精度（如显式 autofill hint + id 术语双重确认）→ 守卫不触发
        assertFalse(
            AutofillDetectionPolicy.shouldSuppressWeakLoginSuggestion(
                hasPasswordField = false,
                loginFieldAccuracies = listOf(Accuracy.HIGHEST),
                manualRequest = false,
            )
        )
    }
}
