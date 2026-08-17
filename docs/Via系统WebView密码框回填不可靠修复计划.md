# Via 系统 WebView 密码框 dataset 回填不可靠修复计划（第二轮）

> 分支：`dev`
> 设备：荣耀 MagicOS / Android 17 / Via 浏览器（mark.via，系统 WebView 内核）
> 现状：上一轮修复（解析器保留密码候选）已生效——日志确认 `hasPasswordTarget=true`、`targetRolePreview=0:PASSWORD=***`、`passwordValuePresent=true`、直填路径、`demotedToAuth=0`。**密码 target 选到了、密码值也解密成功**，但密码框仍填不进去（账户框能填）。
> 参考：Bitwarden 在不开无障碍时也能填 Via/PayPal 密码框。

---

## 1. 现象与日志取证

`bastion_logs_20260817_174142` requestId=2/3（mark.via, www.paypal.com）：

```
[AF] DIAG fillableTargets {fillableTargets=3, hints=PASSWORD, PHONE_NUMBER, EMAIL_ADDRESS,
  hasLoginTargets=true, hasPasswordTarget=true}                              ← 密码 target 已选到 ✓
[AF] Autofill target diagnostics {... targetRolePreview=0:PASSWORD=***,
  1:PHONE_NUMBER, 2:EMAIL_ADDRESS, visibleTargetCount=2}                    ← PASSWORD 在 index 0
[FILLING] Login partition direct-fill built {cipherId=2, fieldCount=3,
  passwordValuePresent=true}                                                ← 密码值解密成功 ✓
[FILLING] FillResponse build result {... demotedToAuth=0, authRequired=false,
  callbackCipherDatasets=0}                                                  ← 走直填，无 setAuthentication
[AF] Fill response ready {targets=3, matches=1, authRequired=true(=settingEnabled)}
```

用户实测：账户填了、密码框空白。日志无 `WebView accessibility fallback dispatched`（因无障碍未开），无 `CALLBACK Returning authenticated`（因走直填无 setAuthentication）。

## 2. 根因（执行层，非解析层）

1. **解析层已修好**：密码 target 选到了、值解密成功。✓
2. **执行层失败**：直填 dataset 用 Tiramisu API（`createForTiramisu` → `Dataset.Builder(Presentations)` + `setField(autofillId, Field.Builder().setValue(AutofillValue.forText(password)).build())`）。**Via 系统 WebView 对该 API 的密码框虚拟节点回填不可靠**——账户框（普通 EditText 虚拟节点）接受 setValue，密码框（type=password 的虚拟节点）吞掉 setValue。这是 Android 13+ `Field.Builder` + 系统 WebView 的已知 quirk。
3. **现有 a11y 兜底被门控**：`BastionAutofillServiceNg` 单条匹配后在 `isWebViewFill && BastionAccessibilityService.isCredentialFillAvailable(...)` 时 dispatch `AccessibilityFillCommandStore`。但 `isCredentialFillAvailable` 要求**用户已开 Bastion 无障碍权限**。用户没开 → 兜底不触发 → 密码框空白。这正是"除非开无障碍才可以"的来源。
4. **Bitwarden 不依赖无障碍**：它在 WebView 场景用 `InlinePresentation`（IME commitText）或认证 dataset 回灌（`setAuthentication` + `EXTRA_AUTHENTICATION_RESULT`）写值，绕开框架 dataset 对 WebView 虚拟节点的不可靠回填。Via 系统 WebView 对 IME commitText 可靠接受。

## 3. 修复方案（执行层，对齐 Bitwarden）

### 方案 A（核心）：WebView 直填场景改走认证 dataset 回灌（setAuthentication），而非纯直填

在 `BastionAutofillServiceNg`：当 `isWebViewFill && passwordsForResponse.size == 1`（Via/PayPal 命中）时，**强制让 partition 走 `requiresAuthentication=true`**（即 `effectiveAuthenticationRequired` 维持，或新增 `forceDatasetAuthForWeb` 标记），使 `buildCipherDataset` 挂 `setAuthentication` → 用户点条目后走 `AutofillCipherCallbackActivity` 回灌 → 该路径内：
- 重新构建 dataset 经 `EXTRA_AUTHENTICATION_RESULT` 回填（与直填同 API，但框架在认证回灌时对 WebView 虚拟节点写入更可靠——Bitwarden 走这条）
- a11y 兜底（若可用）作为第二保险

即：**WebView 单条匹配从"纯直填"改为"认证 dataset 回灌"**，对齐 Bitwarden。原生 App（无 webDomain）仍走纯直填，不受影响。

### 方案 B（加固）：a11y 兜底解耦"无障碍可用"门控的提示

a11y 兜底本身仍需无障碍权限（无法绕开——它是无障碍服务能力）。但方案 A 让主路径不再依赖 a11y，a11y 退回"可选第二保险"。用户不开无障碍时，方案 A 的认证回灌主路径已能填密码。

### 方案 C（可观测）：补执行层日志

- `buildCipherDataset`：dataset 构建时记录 `datasetMode=direct|auth`、`targetCount`、`passwordTargetIndex`。
- `AutofillCipherCallbackActivity`：回灌时记录 `EXTRA_AUTHENTICATION_RESULT dataset returned, fields=N`（已有 `Returning authenticated dataset`，补充 field→value 映射计数）。
- `BastionAutofillServiceNg`：a11y 兜底分支记录 `a11yAvailable=false, skipped`（当 WebView 且无障碍不可用时），便于诊断。

### 方案 D（可选，独立）：WebView 密码框也可尝试 InlinePresentation

Via 系统 WebView 对 IME commitText 可靠。若方案 A 在个别 Via 版本仍不可靠，可在 WebView 场景额外提供 `InlinePresentation`（即便 `isInlineSuggestionsEnabled` 关闭也尝试），让 IME 直接 commitText 密码。属增量优化，不阻塞 A/C。

## 4. 涉及文件

```
必改：
  app/.../autofill_ng/BastionAutofillServiceNg.kt     # 方案 A：WebView 单条→强制认证 dataset 回灌
  app/.../autofill_ng/builder/FillResponseBuilderNg.kt # 方案 A/C：partition 标记走 setAuthentication
可选加固：
  app/.../autofill_ng/AutofillCipherCallbackActivity.kt # 方案 C：回灌日志
  app/.../autofill_ng/builder/AutofillDatasetBuilder.kt # 方案 D：Inline 兜底
```

## 5. 验收

1. dev 提交 → Android CI debug 全绿。
2. 真机 Via 浏览器登录 PayPal，**不开 Bastion 无障碍权限**：点击条目后账户+密码同时填入。
3. 日志应出现：`datasetMode=auth`、`CALLBACK Returning authenticated dataset`、`EXTRA_AUTHENTICATION_RESULT` 回灌成功；a11y 分支 `a11yAvailable=false, skipped`（因未开无障碍，但主路径已填）。
4. 回归：Edge/PayPal、Discord、原生 App 不受影响（原生 App 非 WebView，仍纯直填）。
