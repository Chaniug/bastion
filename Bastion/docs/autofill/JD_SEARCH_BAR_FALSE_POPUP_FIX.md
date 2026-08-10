# 京东搜索栏误弹密码修复

> 日期：2026-08-11
> 分支：dev
> 状态：已实现，待 CI 验证

## 问题描述

用户在荣耀 Android 17 真机上测试 bastion，打开京东 App 后，在京东搜索栏（非登录页面）会弹出京东相关的密码条目。这是误弹——搜索栏不是登录框，不应触发密码建议。

## 根因分析

误弹的完整链路：

```
京东搜索栏（native 或 WebView）
  ↓ 解析器弱解析兜底
USERNAME:LOWEST(0.3) 或 USERNAME:MEDIUM(1.5)
  ↓ weakLoginContext（parser 第 637 行）放行孤立 USERNAME
fillableTargets 含 USERNAME，无 PASSWORD
  ↓ loginTargetCount > 0，跳过 structuredDecision 守卫（第 544 行）
  ↓ hasLoginTargets = true → 进入密码匹配
  ↓ allowPackageMatching=true（native 包，无 webDomain）
按包名匹配京东密码条目 → 弹出
```

**关键环节**：

1. **弱解析兜底**：京东搜索栏被解析器以 `LOWEST`(0.3f) 或 `MEDIUM`(1.5f) 精度兜底为 `USERNAME`
   - WebView `WEB_EDIT_TEXT` 兜底 → `USERNAME:LOWEST`
   - native id 含 "username"/"login" 术语 → `USERNAME:MEDIUM`
   - 系统标准 autofill hint（`AutofillHint.USERNAME`）→ `USERNAME:HIGH(4f)`

2. **`weakLoginContext` 全量放行**（`EnhancedAutofillStructureParserV2.kt` 第 637 行）：
   `weakLoginContext = allowWeakTargets && !hasPasswordInItems && hasLoginTypeField`
   无密码框时，孤立 USERNAME 被全量放行，绕过精度门槛。

3. **包名匹配**（`AutofillRequestContextPolicy.allowPackageMatching`）：
   京东是 native 包（非浏览器、无 webDomain）→ 返回 `true` → 按包名匹配密码条目。

## 修复方案：服务层守卫

在 `BastionAutofillServiceNg.processFillRequest` 中，`fillableTargets` 计算完成后、密码匹配之前，加入守卫逻辑。

### 判断条件

```
非手动请求 && 无密码框 && 所有登录类字段精度 < HIGH
→ return null（不弹密码条目）
```

### 精度阈值选择

| 精度 | 分值 | 来源 | 守卫行为 |
|------|------|------|----------|
| LOWEST | 0.3 | WebView WEB_EDIT_TEXT 兜底 | 抑制 ✅ |
| LOW | 0.7 | 弱信号 | 抑制 ✅ |
| MEDIUM | 1.5 | id 术语（username/login） | 抑制 ✅ |
| HIGH | 4.0 | 系统标准 autofill hint | 不抑制 ✅ |
| HIGHEST | 10 | 多重信号确认 | 不抑制 ✅ |

**阈值定为 HIGH**（即 `< HIGH` 视为弱信号），因为：
- 系统标准 autofill hint（`AutofillHint.USERNAME` 等）精度为 HIGH，是开发者明确标注的登录字段，可信
- 弱解析兜底（LOWEST/MEDIUM）是启发式推断，不可信
- 真实登录页有密码框（`hasPasswordField=true`），守卫直接跳过，不受影响

### 改动文件

1. **`AutofillDetectionPolicy.kt`**：新增 `shouldSuppressWeakLoginSuggestion()` 方法
2. **`BastionAutofillServiceNg.kt`**：在 `processFillRequest` 中调用守卫，`return null` 抑制弹窗
3. **`WeakLoginGuardTest.kt`**：回归单测

### 不受影响的场景

| 场景 | 守卫行为 | 原因 |
|------|----------|------|
| 真实登录页（有密码框） | 不触发 | `hasPasswordField=true` → 守卫直接返回 false |
| 带标准系统 hint 的账号框 | 不触发 | `accuracy >= HIGH` → 守卫返回 false |
| 手动请求（用户长按） | 不触发 | `manualRequest=true` → 守卫直接返回 false |
| 无登录类字段的结构化数据 | 不触发 | `loginFieldAccuracies.isEmpty()` → 守卫返回 false |

## 改动代码

### `AutofillDetectionPolicy.kt` — 新增方法

```kotlin
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
```

### `BastionAutofillServiceNg.kt` — 守卫调用

```kotlin
// 在 structuredDecision 守卫之后、fieldSignatureKey 之前
val hasPasswordInFillable = fillableTargets.any {
    it.hint == FieldHint.PASSWORD || it.hint == FieldHint.NEW_PASSWORD
}
val loginFieldAccuracies = fillableTargets
    .filter { isLoginHint(it.hint) }
    .map { it.accuracy }
if (AutofillDetectionPolicy.shouldSuppressWeakLoginSuggestion(
        hasPasswordField = hasPasswordInFillable,
        loginFieldAccuracies = loginFieldAccuracies,
        manualRequest = isManualRequest,
    )
) {
    AutofillLogger.i("AF",
        "Skip weak login autofill: no password field and all login fields below HIGH accuracy",
        ...)
    return null
}
```

## 回归测试

`WeakLoginGuardTest.kt` 覆盖：

- ✅ 京东搜索栏（LOWEST USERNAME，无密码）→ 抑制
- ✅ native id 术语（MEDIUM USERNAME，无密码）→ 抑制
- ✅ 多个弱信号字段（LOW + MEDIUM，无密码）→ 抑制
- ✅ 真实登录页（有密码框）→ 不抑制
- ✅ 标准系统 hint（HIGH，无密码）→ 不抑制
- ✅ 混合精度含 HIGH → 不抑制
- ✅ 手动请求 → 不抑制
- ✅ HIGHEST 精度 → 不抑制
- ✅ 空登录字段列表 → 不抑制

## 后续建议

如果未来仍有其他 App 的搜索栏误弹（信号特征不同于京东），可考虑：
1. 在 parser 层增强 `isSearchField` / `isSearchContainerNode` 的识别信号
2. 收紧 `weakLoginContext` 的放行门槛（要求至少 2 个登录信号或 1 个 MEDIUM+）
3. 但这些改动影响面广，需充分回归测试后再执行
