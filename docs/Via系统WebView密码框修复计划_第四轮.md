# Via 系统 WebView 密码框修复计划（第四轮，对齐 Bitwarden 源码）

> 分支：`dev`
> 状态：方案 A（认证回灌）已回滚（引入指纹+Edge回归）。方案 B（pre-Tiramisu setValue）premise 错误——Bitwarden 在 Tiramisu+ 也用 setField，API 不是差异点。
> 本轮基于 Bitwarden 源码实证（`FillResponseBuilderImpl.kt` / `FilledPartitionExtensions.kt` / `FilledItemExtensions.kt`）。

---

## 1. Bitwarden 源码实证

- `FillResponseBuilderImpl.build`：每个 partition 调 `filledPartition.buildDataset(authIntentSender = ...)`，`authIntentSender` 来自 `createAutofillCallbackIntentSender(cipherId, context)`——**cipherId 有效就挂 setAuthentication，不区分 vault 锁定**。
- `FilledPartitionExtensions.buildDataset`：`datasetBuilder = Dataset.Builder(); authIntentSender?.let { setAuthentication(it) }`，然后 Tiramisu+ 走 `applyToDatasetPostTiramisu`。
- `FilledItemExtensions.applyToDatasetPostTiramisu`：`datasetBuilder.setField(autofillId, Field.Builder().setValue(value).setPresentations(presentations).build())`——**Tiramisu+ 用 setField，和 Bastion 一样**。pre-Tiramisu 才用 setValue。
- 关键：`filledItems` **带真实值**（value 非 null），挂 setAuthentication 让框架回调 Activity；回调时 vault 已解锁则不触发认证，直接用 filledItems 值构建 dataset 返回 `EXTRA_AUTHENTICATION_RESULT`。

## 2. Bastion 与 Bitwarden 的真正差异

| 维度 | Bitwarden | Bastion（当前） |
| --- | --- | --- |
| setAuthentication 挂载 | 始终（cipherId 有效就挂） | 仅 `partition.requiresAuthentication=true`（vault 锁定）时挂 |
| filledItems 值（挂 auth 时） | 真实值 | vault 锁定时占位 null；解锁时**不挂 auth（纯直填）** |
| 回调触发指纹 | 仅 vault 锁定 | 只要 `requireAuthentication=true` 就指纹 |
| 回调值来源 | partition.filledItems（带值） | 重新解密 + `resolveFilledValues` 按 hint 映射 |

**Via 密码框填不进的根因（执行层）**：Bastion 解锁态不挂 setAuthentication（纯直填），框架对 Via 系统 WebView 密码框虚拟节点的 setField 回填不可靠。Bitwarden 始终挂 setAuthentication + filledItems 带值，框架在"用户点选→回调→回填"路径对 WebView 虚拟节点写入更可靠（该路径框架内部处理与自动直填不同）。

**方案 A 失败原因**：挂了 setAuthentication 但 filledItems 走占位（null）+ 回调 `requireAuthentication=true` 触发指纹 + 回调 `resolveFilledValues` 按 hint 重新映射（Edge 失配）。与 Bitwarden 三处不符。

## 3. 修复方案 C（对齐 Bitwarden，精确复刻）

核心：**WebView 单条匹配时，filledItems 保留真实值 + 挂 setAuthentication + 回调不触发指纹（requireAuthentication=false）+ 回调直接用 filledItems 值回填（不重新映射）**。

### 改动点

1. `FilledDataBuilderNg.fillLoginPartition`：新增 `forceDatasetAuthForWeb`（已回滚，需重加）。为 true 时 partition **挂 setAuthentication 但 filledItems 保留真实值**（不走占位分支）——即 `requiresAuthentication` 拆成两个语义：`shouldSetAuth`（挂 auth）与 `shouldPromptBiometric`（指纹）。简化：新增 `forceDatasetAuthForWeb` 时 filledItems 走真实值分支（非占位），partition 标记 `requiresAuthentication=true` 仅用于触发 setAuthentication 挂载。

2. `FillResponseBuilderNg.buildCipherDataset`：挂 setAuthentication 用 `createCipherAuthPendingIntent(... requireAuthentication = false)`（vault 解锁态，不指纹）。

3. `AutofillCipherCallbackActivity`：`args.requireAuthentication=false` 时不走 `showBiometricPrompt`（已是现状，107 行 `if (args?.requireAuthentication == true)`）。回调时 `resolveAutofillTargets` + `resolveFilledValues` 仍按 hint 映射——但为避免 Edge 失配，**回调优先用 partition filledItems 的值**（若 callback 能拿到原始 filledItems）。需扩展 callback args 携带 filledItems 或在 callback 内用相同 hint 映射但保证与直填一致。

   注：Bitwarden 回调是构建新 dataset 返回 `EXTRA_AUTHENTICATION_RESULT`，值来自 partition.filledItems（带值）。Bastion 回调 `AutofillCipherCallbackActivity` 当前重新解密+映射，需改为"优先用 callbackArgs 携带的 filledItems 值"。

### 约束（对齐 Bitwarden）
- 不触发指纹（vault 解锁态，requireAuthentication=false）。
- 不影响 Edge（filledItems 带真实值，回调用相同值，不重新按 hint 猜）。
- 原生 App（无 webDomain）仍纯直填，不挂 auth，不受影响。

## 4. 涉及文件

```
必改：
  app/.../autofill_ng/builder/FilledDataBuilderNg.kt        # forceDatasetAuthForWeb：挂 auth 但 filledItems 带真实值
  app/.../autofill_ng/builder/FillResponseBuilderNg.kt      # buildCipherDataset：挂 auth 时 requireAuthentication=false（不指纹）
  app/.../autofill_ng/AutofillCipherCallbackActivity.kt     # 回调优先用 filledItems 值回填，避免 Edge 失配
  app/.../autofill_ng/AutofillCipherCallbackActivity.kt Args # 扩展携带 filledItems 或保证 hint 映射一致
  app/.../autofill_ng/processor/AutofillProcessorNg.kt      # 透传 forceDatasetAuthForWeb
  app/.../autofill_ng/BastionAutofillServiceNg.kt           # isWebViewFill && 单条时传 forceDatasetAuthForWeb
测试：
  app/src/test/.../AutofillDropdownClickRegressionGuardTest.kt  # 同步守卫断言
```

## 5. 验收

1. dev 提交 → Android CI debug 全绿（基线 0 失败）。
2. Via/PayPal **不开无障碍**：点条目后账户+密码同时填入，**不触发指纹**。
3. Edge/PayPal、Discord：账户+密码同时填入，不触发指纹，不回归。
4. 原生 App（com.tdx.AndroidNew）不受影响。
5. 日志：`Dataset auth policy for fill forceDatasetAuthForWeb=true`、`CALLBACK Returning authenticated dataset`、`requireAuthentication=false`、无 `showBiometricPrompt`。

## 6. 风险

- 回调值映射若仍用 `resolveFilledValues`，Edge 可能再次失配——故方案 C 第 3 点强调"回调优先用 filledItems 值"。需确认 callbackArgs 能否携带 filledItems（AutofillId 列表+值）。若无法携带，退而求其次：callback 内 `resolveFilledValues` 但保证 hint 与直填一致（当前 Edge 直填 hints=EMAIL_ADDRESS,USERNAME,PASSWORD，callback re-parse 应一致）。
- 若 Via 系统 WebView 对"回调回填"也不可靠（与直填同），则 Bitwarden 也该失败——但用户报告 Bitwarden 能填，故回调路径应可靠。以此为新方案的前提。
