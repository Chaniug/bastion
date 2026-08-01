# 诊断与修复计划：TOTP 在 Edge/WebView 自动填充后不进入输入法剪贴板

> 状态：待用户确认（约定 #5）
> 触发：用户反馈"最新预览版：top 验证码没办法复制出来，Edge 上账户名和密码可以填充，但 top 验证码不出现在输入法剪切板"
> 设备：荣耀 / Android 17

## 一、根因（已定位，分两层）

### 层 1：OTP 自动复制是"默认关闭"的开关
`Bastion/app/src/main/java/com/bastion/app/autofill_ng/AutofillPreferences.kt`
- `isAutoCopyOtpEnabled` 默认 `false`（L310-312）
- `isOtpNotificationEnabled` 默认 `false`（L300-302）

`OtpAutofillSideEffects.kt` 的 `performOtpAutofillSideEffects()` 在 L67-70 直接：
```kotlin
if (!showNotification && !autoCopy) { return }   // 两个都关 → 直接返回，OTP 永不被复制
```
认证填充主路径（`AutofillCipherCallbackActivity.completeCipherAutofill()` @ L347-353）与手动填充路径（`AutofillPickerActivityV2.launchPasswordAutofillSideEffects` @ L1261）都调用它，因此**开箱默认下，任何自动填充都不会把 OTP 写入剪贴板**。

### 层 2：无障碍"临时剪贴板还原"会抹掉已复制的 OTP（Edge/WebView 专属竞态）
`Bastion/app/src/main/java/com/bastion/app/service/BastionAccessibilityService.kt`

`completeCipherAutofill()` 在 Edge（有 webDomain）且无障碍已开启时，**无条件**走无障碍兜底（L304-336）：
1. 协程 A（无障碍兜底）：算出 OTP → 写入跨进程命令 → 广播。无障碍服务随后对浏览器窗口的字段用 `setNodeText()` 注入。
2. 协程 B（OTP 复制）：`performOtpAutofillSideEffects` 写剪贴板（仅当 autoCopy 开）。

`setNodeText()`（L793-835）对空字段走 `setTemporaryClipboard()` + `ACTION_PASTE` + `scheduleTemporaryClipboardRestore()`（L876-887，**500ms 后把剪贴板还原到填充前快照**）。

**时序**：协程 B 约 ~500ms 内完成 OTP 复制；而协程 A 的无障碍注入发生在认证 Activity 关闭、用户回到 Edge 之后（约 1–3s）。于是：
- 即便用户开了 `autoCopy`，无障碍的"剪贴板还原"也会在数秒后**把刚复制的 OTP 抹掉**。
- 若 `autoCopy` 关（默认），OTP 根本没复制过，自然也不在剪贴板。

两条叠加上，Edge 上的 OTP 永远进不了（输入法）剪贴板——与用户现象完全吻合。

> 备注：认证器列表里的手动复制（`TotpItemCard.onCopyCode` → 原始 `ClipboardManager.setPrimaryClip`，label `"TOTP Code"`，**不设 `IS_SENSITIVE`**）能正常进系统剪贴板，不是本 bug 的成因；自动填充复制路径 `OtpAutofillSideEffects.writeClipboard` 同样不设 `IS_SENSITIVE`，因此也不是"输入法因 sensitive 标记而忽略"的问题。

## 二、修复方向（需用户确认取舍）

### 方向 A（推荐）：自动填充后"总是"把 OTP 放进剪贴板 + 修无障碍竞态
对齐 Bitwarden 行为——既然用户刚刚在该站点自动填充了带 TOTP 的凭据，2FA 码理应在剪贴板里供粘贴。
1. 让 OTP→剪贴板的动作**脱离 `autoCopy` 开关**（仅对"显式自动填充"这一情形），或在 `autofill` 填充主路径默认开启 OTP 自动复制。
2. 修无障碍竞态：当本次填充命令带 OTP 且 OTP 已注入字段（或用户需粘贴到别处）时，**取消挂起的剪贴板还原**并显式把 OTP 写入剪贴板（非临时、不设 `IS_SENSITIVE`），保证不被抹掉。

### 方向 B：保持 OTP 自动复制为"可选"，但修好无障碍竞态
- 不改变隐私默认；用户需在设置里开启"自动复制 OTP"。
- 但修好层 2 的竞态，使开启后 OTP 在 Edge 上能存活于剪贴板。
- 缺点：默认仍不复制，用户报的"开箱即用"问题未根本解决（需先去开设置）。

### 方向 C：仅把 `isAutoCopyOtpEnabled` 默认值翻为 `true`（不修竞态）
- 最小改动；但 Edge/无障碍路径下 OTP 仍会被还原抹掉，**问题未真正解决**，不推荐。

## 三、建议落地（若选 A）
1. `OtpAutofillSideEffects.kt`
   - `performOtpAutofillSideEffects`：在认证/手动填充主路径，对 OTP 复制去掉 `!autoCopy` 的硬门控（或新增"填充即复制"语义），保留 `showNotification` 仍走原开关。
   - `writeClipboard`：保持不设 `IS_SENSITIVE`，并接入现有"敏感内容自动清除"计时（保持与 `ClipboardUtils` 一致的安全期）。
2. `BastionAccessibilityService.kt`
   - `fillCredentialsInActiveWindow`：若 `otp.isNotBlank()`，填充后调用"取消挂起还原 + 显式写 OTP 到剪贴板"的收尾，避免被 L876 的 500ms 还原抹掉。
   - 收尾写剪贴板走非临时、不设 `IS_SENSITIVE` 的通道，确保出现在输入法剪贴板。
3. 回归看护
   - 新增/调整 guard 测试，固化"填充主路径触发 OTP 复制"与"无障碍填充后剪贴板含 OTP"的语义（沿用 `docs/guard-test-validation/` 校验工具）。

## 四、待确认问题
- 是否接受"自动填充后默认把 OTP 放进剪贴板"（方向 A，对齐 Bitwarden）？
- 还是保持可选、只修竞态（方向 B）？
- OTP 复制后的"自动清除"时长沿用现有敏感默认值即可？

## 五、修复记录（已实施，方向 A）
- 用户确认采用"默认即复制 + 修竞态"。
- 改动（commit `6b6998cd`，分支 `dev`）：
  1. `AutofillPreferences.kt`：`isAutoCopyOtpEnabled` 默认 `false` → `true`（对齐 Bitwarden）。
  2. `BastionAccessibilityService.kt`：新增 `leaveOtpInClipboard(otp)`，在
     `fillCredentialsInActiveWindow` 填充收尾、OTP 存在时调用；取消挂起的临时剪贴板还原
     （`clipboardHandler::removeCallbacks` + `resetTemporaryClipboardSessionLocked()`）并把
     OTP 显式写入系统剪贴板（label `"OTP Code"`，**不设 `IS_SENSITIVE`** 以保输入法剪贴板收录）。
  3. 新增 `OtpAutofillClipboardRegressionGuardTest.kt` 固化上述行为。
- CI：`30712842822` **success**（dev 分支）。
- 后续：用户下载预览版 APK 在荣耀 / Android 17 真机验证 Edge 上 OTP 复制；确认无误后
  再将 `dev` 合并到 `main`（约定 #2）。
