# Edge 浏览器 Discord 登录"只填用户名、密码空白"Bug 修复计划

> 分支：`dev`（计划阶段，待用户确认后实施）
> 设备：荣耀 MagicOS / Android 17 / Edge (com.microsoft.emmx)
> 现象：Edge 登录 Discord 时，凭据条目正常弹出，点击后用户名框填入、密码框空白，TOTP 验证码正常复制。
> 参考：Bitwarden 开源 autofill 填充设计（`AutofillCompletionManagerImpl` / `FilledAutofillItemCollection`）

---

## 1. 现象与日志取证

`bastion_logs_20260817_165339` 中 requestId=2/3（discord.com）：

```
[AF] Autofill target diagnostics {... webDomain=discord.com, webView=true,
  targetCount=3, loginTargetCount=3,
  targetRolePreview=0:USERNAME:MEDIUM:focused:visible,1:USERNAME:MEDIUM:focused:visible,2:PASSWORD=***,
  ...}
[FILLING] FillResponse build result {cipherDatasets=1, fillableIds=3, suggestedIds=1, authRequired=false, ...}
[AF] Fill response ready {targets=3, matches=1, authRequired=true(注：此处记录的是设置项 settingEnabled，非 effective)}
```

- 解析出 3 个可填字段：2×USERNAME + 1×PASSWORD
- 单条凭据匹配（matches=1）→ `effectiveAuthenticationRequired=false`
- 走非认证直填路径（`buildCipherDataset`，无 `setAuthentication`）
- `suggestedIds=1` = 1 个 partition 有 cipherId（正常，单条匹配即 1 partition）

## 2. 根因

填充链路：`BastionAutofillServiceNg` → `bwCompatProcessor.process` → `FillResponseBuilderNg.build` → `FilledDataBuilderNg.build` → `fillLoginPartition`。

关键在 `FilledDataBuilderNg.fillLoginPartition` 的 **非认证直填分支**（`requiresAuthentication=false`）：

```kotlin
} else {
    autofillViews.mapNotNull { autofillView ->
        val value = when (autofillView) {
            is AutofillView.Login.Username -> autofillCipher.username
            is AutofillView.Login.Password -> autofillCipher.password
        }
        autofillView.buildFilledItemOrNull(value = value)   // value 空则返回 null → 被过滤
    }
}
```

`buildFilledItemOrNull`：`if (value.isNullOrBlank()) return null`。

而 `autofillCipher.password` 来自 `buildCipherForResponse`：

```kotlin
val passwordValue = decryptForAutofill(entry.password)   // AutofillSecretResolver.decryptPasswordOrNull
...
return entry.toAutofillCipherLogin(
    usernameValue = usernameValue.orEmpty(),
    passwordValue = passwordValue.orEmpty()   // 解密失败 → null.orEmpty() = ""
)
```

`AutofillSecretResolver.decryptPasswordOrNull`：当解密失败且载荷形似密文时**返回 null**（注释 "Never returns encrypted payload as fillable password"）。

**结果链：密码解密失败 → `cipher.password = ""` → `buildFilledItemOrNull("")` 返回 null → 密码字段被 `mapNotNull` 丢弃 → `filledItems` 只剩用户名字段 → dataset 的 `fields` 不含密码 autofillId → 框架只填用户名、密码框空白。** 静默丢弃，无降级、无兜底、无错误日志提示半填充。

TOTP 正常复制是因为走独立的 OTP 解密路径（`generateOtpCodeForPassword`），与密码字段解密互不影响，故不能作为"vault 已解锁、密码必能解密"的反证。

> 注：本结论基于代码路径推断。日志的 System Logcat 段未覆盖填充发生时刻（16:49–16:50），缺 `BastionOtpCopy`/`dataset direct-fill`/`AutofillSecret Password decrypt failed` 行作直接佐证。修复后建议复测时同步抓取该时刻 logcat。

## 3. 与 Bitwarden 设计差异

Bitwarden 填充构建（`FillResponseBuilder` / `FilledAutofillItemCollection`）：

- dataset 对**所有可填目标 autofillId** 都建立字段映射，即使某字段值为空也保留占位（或转走认证 dataset），**绝不因单字段空而整体丢弃该字段映射**。
- 值缺失/解密失败时走"认证 dataset + Activity 回灌"或"a11y 兜底"，保证用户名+密码**同批写入**。
- Bastion 的 `buildVaultItemDataset`（手动填充入口）已采纳这套占位兜底（`MANUAL_PLACEHOLDER_VALUE` + 覆盖所有 `fillableAutofillIds`），但**直填 dataset 路径（`buildCipherDataset`）没有采纳**，这是半填充的根源。

## 4. 修复方案（分层，最小侵入）

### 方案 A（核心，必做）：直填路径不再静默丢弃空密码字段

`FilledDataBuilderNg.fillLoginPartition` 非认证分支：当某字段 value 为空时，**不要 mapNotNull 丢弃**，改为：

1. 优先保留字段进 `filledItems`（value=null 占位），让 dataset 仍持有该 autofillId；框架对 value=null 的字段会忽略写入但保留 dataset 整体一致性——**但这不解决密码空白**，仅防字段数错位。
2. 更稳妥：当**密码解密失败/为空**时，将该 partition 标记 `requiresAuthentication=true`，强制走 `AutofillCipherCallbackActivity` 回灌路径——callback 路径会重新解密并经 a11y 兜底注入密码（`AccessibilityFillCommandStore`，WebView 专用，Discord/Edge 命中），与 OTP 复制共用进程级作用域，能可靠写值。

即：**密码解密失败 → 降级为认证回灌 + a11y 兜底**，而非静默直填半填充。对齐 Bitwarden"值缺失转认证"策略。

### 方案 B（加固，建议同做）：直填 dataset 仍覆盖全部 fillableIds

`FillResponseBuilderNg.buildCipherDataset` 的 `fields` 构建：即使某 `filledItem.value=null`，也把该 autofillId 写入 dataset（value=null），保证 dataset 字段集 = fillableIds，避免框架因字段缺失降级。配合方案 A，密码字段要么有值直填、要么 null 占位 + 整 partition 走认证回灌。

### 方案 C（可观测性，必做）：补诊断日志

- `FilledDataBuilderNg.fillLoginPartition`：当某字段 value 为空被处理时，打 `AutofillLogger.w("FILLING","Login field value empty, field kept/demoted", metadata=role=PASSWORD, cipherId, reason=password_decrypt_failed_or_blank)`。
- `AutofillSecretResolver.decryptPasswordOrNull`：解密失败分支已有 `Log.w`，同步写 `AutofillLogger` 持久化，便于下次取证。
- `FillResponseBuilderNg` build result：新增 `passwordValuePresent` / `demotedToAuth` 字段，一眼看出是否半填充降级。

### 方案 D（可选，后续）：Discord 双 USERNAME 字段解析复核

日志显示 `hints=USERNAME, USERNAME, PASSWORD`（2 个用户名）。Discord 登录页通常 1 用户名 + 1 密码。多出的 USERNAME 可能是隐藏的注册/找回入口被误识别。可在 `EnhancedAutofillStructureParserV2` 对同 partition 内多个 USERNAME 做可见性/焦点去重。属独立优化，不阻塞 A/B/C。

## 5. 涉及文件

```
必改：
  app/.../autofill_ng/builder/FilledDataBuilderNg.kt        # 方案 A：空值字段降级认证回灌
  app/.../autofill_ng/builder/FillResponseBuilderNg.kt      # 方案 B：dataset 覆盖全 fillableIds；方案 C 日志
  app/.../autofill_ng/AutofillSecretResolver.kt             # 方案 C：解密失败持久化日志
可选：
  app/.../autofill_ng/EnhancedAutofillStructureParserV2.kt  # 方案 D：USERNAME 去重（独立）
```

## 6. 验收

1. dev 提交 → GitHub Actions（Android CI debug）全绿。
2. 真机复测：Edge 登录 Discord，点击 Bastion 条目后**用户名+密码同时填入**，TOTP 仍正常复制。
3. 日志应出现：`FillResponse build result` 含 `passwordValuePresent=true` 或 `demotedToAuth=true`；若降级则见 `CALLBACK Returning authenticated dataset` + `Accessibility fallback command dispatched`。
4. 回归：原生 App（如 com.tdx.AndroidNew）填充不受影响（无 webDomain，不走 a11y 兜底）。
