# Via 浏览器 PayPal 登录"密码框填不进"Bug 修复计划

> 分支：`dev`
> 设备：荣耀 MagicOS / Android 17 / Via 浏览器（mark.via，系统 WebView 内核）
> 现象：Via 登录 PayPal 时账户能填、密码框填不进去，必须开 Bastion 无障碍权限才能填密码。Edge 浏览器同站正常。Bitwarden 在 Via/Edge 都能填。
> 参考：Bitwarden autofill 字段选择策略（不因同组账户/密码冲突而丢弃密码候选）

---

## 1. 现象与日志取证

`bastion_logs_20260817_172459` Persisted 段 requestId=4（mark.via, www.paypal.com）：

```
[PARSING] Parser field selection {candidateCount=15, ..., candidates=...USERNAME:HIGH, ...PASSWORD=***(×5), hasPasswordInItems=true}
[PARSING] Explicit account evidence overrode conflicting password evidence
  {selectedHint=USERNAME, candidateRoles=USERNAME:4.0:HIGH,PASSWORD=***, focused=false, visible=true}
[AF] DIAG fillableTargets {fillableTargets=3, hints=EMAIL_ADDRESS, USERNAME, PHONE_NUMBER, hasPasswordTarget=false}
[AF] Autofill target diagnostics {... targetRolePreview=0:EMAIL_ADDRESS, 1:USERNAME, 2:PHONE_NUMBER, ...}  ← 无 PASSWORD
[FILLING] Login partition direct-fill built {cipherId=2, fieldCount=3, passwordValuePresent=false}
```

对照 Edge（com.microsoft.emmx）同站 requestId=4：
```
fillableTargets=3, hints=EMAIL_ADDRESS, USERNAME, PASSWORD, hasPasswordTarget=true
targetRolePreview=...2:PASSWORD=***
Login partition direct-fill built {passwordValuePresent=true}  ← 正常
```

**关键差异：Via 把密码候选丢了个干净（hasPasswordTarget=false），Edge 保留了密码 target。**

## 2. 根因

`EnhancedAutofillStructureParserV2` 按 `groupBy { it.id }`（autofillId）分组，每组调 `AutofillFieldRolePolicy.selectWithDiagnostics` 选一个角色。该策略（`AutofillFieldRolePolicy.kt:28-39`）：

```kotlin
val hasPasswordCandidate = candidates.any { it.hint.isPasswordHint() }
val explicitAccountCandidates = candidates.filter { isAccountHint() && score >= MEDIUM }
val eligibleCandidates = if (hasPasswordCandidate && explicitAccountCandidates.isNotEmpty()) {
    explicitAccountCandidates   // ← 丢弃密码候选，只留账户候选
} else { candidates }
```

**机制**：同组同时存在密码候选 + 账户候选时，认定密码是噪声，丢弃密码、只从账户候选里选。在大多数原生 App/标准 WebView 下这是对的（容器内误判的密码框）。但 Via 系统 WebView 在 PayPal 登录页给用户名框和密码框分配了**相同 autofillId**（DOM 节点复用 / iframe），导致两者归入同一组 → 密码候选被整体丢弃 → `fillableTargets` 无 PASSWORD → dataset 不含密码 autofillId → 框架不填密码框。开无障碍才能填是因为 a11y 走"按节点文本/类型识别密码框"的独立路径，绕开了解析器分组。

Edge 给用户名/密码框分配不同 autofillId，分组后各自独立，密码不被丢 → 正常。

> 注：这是与上一轮"直填半填充"不同的独立 bug。上一轮是"密码被选进 target 但解密值为空被丢弃"；这一轮是"密码压根没被选进 target"。两者都在 PayPal/Via 表现为密码填不进，但根因不同，本轮修复点在解析器角色选择层。

## 3. 与 Bitwarden 差异

Bitwarden 字段选择不依赖"同 autofillId 分组内二选一"：密码框只要 hint=password 且非 forceAutofillOff，就作为独立 fillable target 保留，不因同组有账户候选而删除。Via+PayPal 同 autofillId 的用户名/密码被 Bitwarden 视为两个独立可填目标（密码框照填），所以 Bitwarden 能填。

## 4. 修复方案（最小侵入，对齐 Bitwarden）

**核心**：`AutofillFieldRolePolicy.selectWithDiagnostics` 在"账户覆盖密码冲突"时，不彻底丢弃密码候选，而是**额外返回被丢弃的最高分密码候选**，由调用方追加为独立 ParsedItem。

### 改动点

1. `AutofillFieldRolePolicy.kt`：`AutofillFieldRoleSelection` 增加 `droppedPasswordCandidate: T?` 字段；`selectWithDiagnostics` 在 `resolvedExplicitAccountPasswordConflict=true` 时，从被过滤掉的密码候选里取最高分者填入该字段返回。

2. `EnhancedAutofillStructureParserV2.kt`（768-821 行附近）：当 `fieldRoleSelection.droppedPasswordCandidate != null` 时，把该密码候选也追加为一个 `ParsedItem(hint=PASSWORD, ...)`（id 仍用同组 id——同 autofillId 多 target 框架会处理；若框架对同 id 多 hint 有歧义，则密码 target 优先级在账户之后但保留可填）。这样 `fillableTargets` 会包含 PASSWORD，dataset 能写密码框。

3. 可观测：`selectWithDiagnostics` 已有 WARN 日志，补充 `droppedPasswordKept=true` 字段表明密码候选被保留而非丢弃。

### 边界与回归

- 标准 WebView / 原生 App：同组通常只有单一角色，不触发冲突分支，行为不变。
- 真正误判的密码候选（容器内噪声）：被保留为独立 target 后，dataset 会多一个密码 autofillId。但该 id 若非真密码框，框架写入会被目标框忽略（非密码框不接受 password 值），不会造成错误填充，仅多一条无效 target。可接受，且与 Bitwarden 行为一致。
- 同 autofillId 多 hint：Android 框架对同 id 的 dataset setValue 以最后一次为准，但本修复让账户与密码作为**两个独立 ParsedItem**进入 fillableTargets，最终在 `fillLoginPartition` 里各自映射到 view（Username/Password），分别 setValue 到**对应 autofillId**。若两者 id 相同，则同 id 会被写入两次（账户值 + 密码值）——这点需在 `fillLoginPartition` 确认是否按 view 角色去重。验收时重点测 PayPal/Via。

## 5. 涉及文件

```
必改：
  app/.../autofill_ng/AutofillFieldRolePolicy.kt              # 返回被丢弃的密码候选
  app/.../autofill_ng/EnhancedAutofillStructureParserV2.kt    # 追加密码 ParsedItem
可选加固：
  app/.../autofill_ng/builder/FilledDataBuilderNg.kt          # 确认同 autofillId 多 view 的填充映射
```

## 6. 验收

1. dev 提交 → Android CI debug 全绿。
2. 真机 Via 浏览器登录 PayPal：点击 Bastion 条目后**账户+密码同时填入**，无需开无障碍。
3. 日志应出现：`fillableTargets` 含 PASSWORD、`hasPasswordTarget=true`、`targetRolePreview` 含 PASSWORD、`Login partition direct-fill built {passwordValuePresent=true}`。
4. 回归：Edge/PayPal 仍正常；原生 App（如 com.tdx.AndroidNew）不受影响。
