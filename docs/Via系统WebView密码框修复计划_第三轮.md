# Via 系统 WebView 密码框修复计划（第三轮，回滚 A 后重设计）

> 分支：`dev`
> 状态：方案 A（认证回灌）已回滚——引入"每次指纹解锁"+ Edge 账户名填不进两个回归，放弃。
> 设备：荣耀 MagicOS / Android 17 / Via（系统 WebView）/ PayPal

---

## 1. 方案 A 为何失败（回滚原因）

方案 A：WebView 单条匹配强制走 `setAuthentication` 认证回灌（`EXTRA_AUTHENTICATION_RESULT`）。
- 回归 1：每次填充触发 `AutofillCipherCallbackActivity` 认证（指纹），纯直填本不需要。
- 回归 2：Edge 之前直填账户+密码正常，走回灌后账户名填不进（`resolveFilledValues` 按 hint 映射在 Edge 上失配）。
- 结论：认证回灌对 Via 系统 WebView 并不比直填更可靠，且引入认证开销 + Edge 回归。前提假设错误。

## 2. 重新分析：Via 密码框为什么直填写不进

- 解析层已修好（密码 target 选到、值解密成功，日志 `passwordValuePresent=true`）。
- 执行层：Bastion 在 API 33+（荣耀 37）走 `createForTiramisu` → `Dataset.Builder(Presentations).setField(autofillId, Field.Builder().setValue(value).build())`。Via 系统 WebView 对 `setField` + 虚拟密码框节点的回填有 regression（账户框虚拟节点可写、密码框吞值）。
- Bitwarden 不开无障碍能填 Via：不靠 inline（Via `inlineSuggestionsRequest=null`）、不靠认证回灌。最可能是它对 dataset 用经典 `setValue(autofillId, value, presentation)` API（API 26+，系统 WebView 长期稳定支持），而非 `setField`。

## 3. 修复方案 B（新，低风险）

**WebView 场景 dataset 强制用 pre-Tiramisu 的 `setValue` API**（即便 SDK>=33）：
- `AutofillDatasetBuilder.create` 增加 `forceLegacySetApi: Boolean`；为 true 时走 `createPreTiramisu`（`Dataset.Builder(menuPresentation).setValue(autofillId, value, presentation)` + inline）。
- `FillResponseBuilderNg.buildCipherDataset` / `buildVaultItemDataset` 在 `request.webView == true`（或 caller 传入 `isWebViewFill`）时传 `forceLegacySetApi=true`。
- 不引入认证（无 setAuthentication）→ 不触发指纹。
- 不改值映射 → 不影响 Edge（setValue 对 Edge 同样有效，账户+密码都填）。
- 原生 App（无 webDomain）仍走 `setField`，不受影响。

### 风险与验证
- `setValue` 在 Tiramisu+ 仍可用（framework 保留旧 API 兼容）。最坏情况：Via 仍不写密码 → 回退到 a11y 兜底（需开无障碍），但不会比现状更差，且不引入 Edge/指纹回归。
- 验收：Via/PayPal 不开无障碍填账户+密码；Edge/PayPal、Discord 仍正常；原生 App 不受影响。

## 4. 涉及文件

```
必改：
  app/.../autofill_ng/builder/AutofillDatasetBuilder.kt   # create 增加 forceLegacySetApi 分发
  app/.../autofill_ng/builder/FillResponseBuilderNg.kt    # WebView 场景传 forceLegacySetApi
可选：
  app/.../autofill_ng/model/AutofillRequest.kt           # 若需 request.webView 标记
```

## 5. 若方案 B 仍无效的备选（方案 D，后续）

Via 场景额外提供 `InlinePresentation`（用兜底 spec 即便 `inlineSuggestionsRequest=null`）——但 Via 输入法若不支持 inline 仍无效。或保留 a11y 作为"可选增强"（用户主动开无障碍时获得额外可靠性），不作为主路径。
