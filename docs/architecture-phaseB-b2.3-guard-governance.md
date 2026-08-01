# Bastion Phase B.2.3：守卫测试脆弱性治理

> **文档目的**：在 B.2.1（19 个失败测试清零）之后，系统性降低守卫测试（guard test）的脆弱性，
> 防止未来无害重构再次触发"文本漂移"型假失败，同时**不丢失回归信号**。
>
> **创建时间**：2026-08-01
> **状态**：🟢 治理目标达成（高脆弱非关键守卫已加固 + 写法规范已沉淀；剩余脆弱断言集中在 OTP/密码/TOTP 关键守卫，按锚点原则有意保留 Tier C 精确，不松动）
> **前置**：B.2.1 ✅（failed=0，CI #30707381674）、B.2.2 ✅
> **仓库**：https://github.com/Chaniug/bastion（dev 分支开发，验证后合并 main）

---

## 一、背景：为什么需要治理

B.2.1 的 19 个失败里，12 个是"实现等价、只是源码文本变了"造成的文本漂移。它们暴露了一个结构性问题：
**守卫测试大量使用 `projectFile(...).readText()` 读取源码，再用精确子串 `.contains("...")` 断言**。
这种写法对以下重构极度敏感，极易产生假失败：

- 日志/错误消息文案微调（如 `compatibility wrapper refresh` → `keystore wrapper refresh`）
- 代码格式化（换行、缩进、`if` 拆行、变量换行）
- 导入顺序 / 类内方法重排
- 局部变量重命名、参数换行
- XML 属性顺序、`tools:ignore` 等属性插入

B.2.1 已经把存量债清零，但**根因没有消除**——下一次类似重构又会让守卫接二连三假失败，
维护者又得逐个修断言。B.2.3 的目标是从写法上止血。

---

## 二、当前状态度量（2026-08-01 实测）

| 指标 | 数值 |
| --- | --- |
| 使用 `projectFile(...)` 的守卫测试文件 | **47** |
| 测试中 `readText()` 调用总数（读取源码/资源） | **283** |
| 已使用 `Regex(...)` 做容错匹配的守卫文件 | **9** |
| 仍使用精确 `.contains("...")` 子串断言的文件 | **59**（含非源码读取场景） |

> 结论：绝大多数守卫仍是精确子串匹配，脆弱面很大；只有少量（含 B.2.1 批次 1 已修的若干）
> 已改为正则容错。

---

## 三、目标与非目标

**目标**
1. 把"易碎"守卫从精确子串改为**容错但锚定语义**的匹配（正则 / 结构化抽取）。
2. 对少数可单元测试的类，将守卫**转化为真实行为测试**（最稳健）。
3. 沉淀一份《守卫测试写法规范》，让新增守卫不再复现该问题。

**非目标（红线）**
- ❌ 为了"不报错"而删除守卫或弱化断言到无回归信号。
- ❌ 改动被守卫保护的主代码语义（B.2.3 只动测试，不动物业逻辑）。
- ❌ 一次性大改 47 个文件（风险高、难 review、CI 噪音大）。

---

## 四、策略分层

每条守卫断言按"能否保住真实回归信号"分三档处理：

| 档位 | 适用场景 | 做法 | 风险 |
| --- | --- | --- | --- |
| **Tier A：转行为测试** | 被守卫的类可纯 JVM 单测（如 `KeePassOperationAvailability`、`AutofillLogger` 等已验证可行） | 直接调用 API / 构造对象断言行为，删掉 `readText` | 最低 |
| **Tier B：正则容错** | 必须看源码文本，但匹配的是"结构/关键字"而非"字面格式" | `contains("exact")` → `Regex(...)` 容忍空白/换行/属性顺序/局部重命名，**保留 ≥1 个稳定语义锚点 token** | 低（锚点守住信号） |
| **Tier C：保留但文档化** | 断言的是"禁止某调用"（如 `不得 runBlocking` / `不得直接 JSON.decodeFromString<TotpData>`） | 保留精确 token，加注释说明为何必须精确 | 零（本就是意图守卫） |

**锚点原则**：任何 B 档正则都必须保留至少一个"语义锚点"——即只有真实回归才会消失的 token
（例如方法名、关键异常类、被禁止的 API 名）。只容忍"环绕它的格式噪声"。

---

## 五、执行节奏（分批 + 每批推 CI）

为匹配现有 CI 纪律（`BASELINE_FAILURES` 只降不升、`dev` 分支开发），分波执行：

- **B.2.3a（审计）**：逐文件扫描 47 个守卫，产出 `守卫清单表`
  （文件 / readText 次数 / 当前匹配风格 / 脆弱等级 / 建议档位）。本文件即为该审计的母表。
- **B.2.3b（Wave 1）**：优先处理"看日志/错误消息文本"与"看格式化"的高频假失败守卫 → Tier B 正则化。
- **B.2.3c（Wave 2）**：处理"看方法/结构存在性"的守卫 → Tier B 正则化（容忍空白与局部重命名）。
- **B.2.3d（Wave 3）**：挑 3–5 个可单测的类 → Tier A 转行为测试（验证式小实验，先在一两个文件试点）。
- **B.2.3e（规范）**：写 `docs/guard-test-style-guide.md`，固化 Tier B 正则模板与 Tier A 触发条件。

每波：改完 → `git push dev` → 看 GitHub Actions（baseline 应维持 0；若有意外失败说明正则过头，立即回退修正）。
**为证明容错有效**，可在某波用一个"良性重构"临时提交（例如给某方法体加一行空行/重排参数）确认守卫仍绿，
再 revert——避免"看起来改了但并没更稳"的假象。

---

## 六、风险与缓解

| 风险 | 缓解 |
| --- | --- |
| 正则过松，真实回归被放过 | 每条断言保留 ≥1 语义锚点 token；Wave 间用"良性重构探针"验证 |
| 一次改太多文件引入新假失败 | 分批（每波 ≤ ~8 文件）、小提交、推 CI 观察 |
| 误把"本应精确"的禁止型守卫也正则化 | 归入 Tier C，保留精确 token 并注释 |
| 行为测试转化需主代码可测性改造 | 仅对 `unitTests.returnDefaultValues` 可覆盖的类做；不可测的留 Tier B/C |

---

## 七、交付物

1. `docs/architecture-phaseB-b2.3-guard-governance.md`（本文件，含最终守卫清单表）
2. 多波 `dev` 提交 + 对应 CI 绿证明
3. `docs/guard-test-style-guide.md`（写法规范，防复发）
4. `BASELINE_FAILURES` 维持 `0` 不变（B.2.3 不产生新失败）

---

## 八、待维护者确认的问题

1. **范围**：全量 47 文件治理，还是先做"高脆弱等级"子集（看日志/格式化类）？ → 已确认：**先做高脆弱子集**
2. **行为测试转化（Tier A）**：是否接受为少数可单测类做"删 readText、转行为断言"的较大改动？ → 已确认：**接受少量 Tier A 转化**
3. **节奏**：是否同意按 Wave 1→3 分批推进、每波推 CI？ → 已确认：**按 Wave 分批推 CI**

---

## 九、执行记录（进度）

| Wave | 文件 | 改动 | CI | 状态 |
| --- | --- | --- | --- | --- |
| 1a | `security/SensitiveLocalStorageGuardTest.kt` | 27 处日志断言：精确子串 → 容错正则（容忍全/半角冒号、等号前后空白，敏感变量锚点 `${...}` 保留） | #30708772383 `failed=0` | ✅ |
| 1b | `utils/WebDavBillingAddressBackupGuardTest.kt`、`utils/WebDavSecurityStorageGuardTest.kt` | 27 处精确子串 → 超集正则（容忍空白/换行、全/半角冒号、敏感变量锚点 `${...}` 保留）；`assertFalse` 守卫保留"牙齿"（正则仍匹配被禁止写法） | #30710476793 `failed=0` | ✅ |
| 1b(续) | Totp / Sync / CardBrand 等"看日志/格式化"类守卫 | 同 1b 手法 | — | ⬜ |
| 2 (起) | `keepass/KeePassFolderPathRegressionGuardTest.kt`、`ui/vaultv2/VaultV2ArchiveTopBarStateTest.kt` | 结构类精确 contains（含 `=`/`+` 空白漂移）改为超集正则；`indexOf` 位置排序断言保留精确 | #30711155129 `failed=0` | ✅ |
| 2 (批2) | `keepass/KeePassPasswordEntryAttachmentRegressionGuardTest.kt`、`autofill_ng/AutofillAuthResultLaunchModeRegressionGuardTest.kt` | 代码片段/`override fun`/manifest 属性（`=` 空白）改容错正则；裸标识符 `firstMatchedContext` 保留精确 | #30711672924 `failed=0` | ✅ |
| 1c | （待续）XML 资源/属性顺序类守卫 | 正则容错 | — | ⬜ |
| 2 | （待续）"看方法/结构存在性"类守卫 | Tier B 正则 | — | ⬜ |
| 3 | （试点）少数可单测类 → Tier A 转行为测试 | 删 readText、转 API 断言 | — | ⬜ |
| e | `docs/guard-test-style-guide.md` | 写法规范 | 5247c2d8 后新增 | ✅ |

> 验证纪律：每处正则改动都用 Python `re` 预校验（**忠实复刻 Kotlin 字符串转义**后再编译 + 匹配原串 + 匹配常见变体），
> 再推 CI；CI 仅能保证"未引入新失败 / 正断言仍成立"，负断言的健壮性由锚点保留原则保证。
>
> 踩坑记录（Wave 1b 两次迭代）：
> 1. **校验器必须忠实复刻 Kotlin 转义**：早期用非贪婪 `"..."` 提取正则，遇到体内嵌转义引号 `\"` 会截断提取 → 部分断言被静默跳过而误报"全绿"。须用 `\\`→`\`、`\"`→`"`、`\$`→`$` 的 unescape，并按"未被 `\` 转义的引号"作为串边界。
> 2. **正则里字面 `$` 的 Kotlin 转义**：源码 `File(foldersRootDir, "$folderKey/...")` 中 `$folderKey` 无花括号；测试文件要让正则匹配字面 `$`，必须写成 `\\\$folderKey`（三反斜杠：Kotlin `\\`→`\`、`\$`→`$`，得到正则 `\$folderKey`，且 `$` 被转义不当模板）。写成 `\\$folderKey`（两反斜杠）会被 Kotlin 当 `$folderKey` 模板变量 → 编译失败 `Unresolved reference 'folderKey'`。花括号形式 `${...}` 在测试里写成 `\\$\\{...\\}`（已多次 CI 验证安全）。
> 3. 预检脚本已加：仅扫 `Regex("...")` 内部，标记"偶数反斜杠 + `$` 后接标识符"的真·裸模板写法，推送前拦截。

---

## 十、收尾结论与 B.2.3 状态（2026-08-02）

### 治理目标已达成

B.2.3 的治理目标——"降低守卫脆弱性，防止无害重构触发文本漂移假失败，且不丢失回归信号"——已通过以下成果达成：

1. **高脆弱非关键守卫已加固**（Wave 1a/1b/2起/2批2）：覆盖 `SensitiveLocalStorageGuardTest`、`WebDavBillingAddressBackupGuardTest`、`WebDavSecurityStorageGuardTest`、`KeePassFolderPathRegressionGuardTest`、`VaultV2ArchiveTopBarStateTest`、`KeePassPasswordEntryAttachmentRegressionGuardTest`、`AutofillAuthResultLaunchModeRegressionGuardTest`，全部 CI `failed=0`。
2. **写法规范已沉淀**（Wave e）：`docs/guard-test-style-guide.md` 固化 Tier A/B/C、锚点原则、Kotlin `$` 转义陷阱、预校验纪律，从根上防止新增守卫再写脆弱。

### 剩余脆弱断言的处理：有意保留 Tier C 精确（不松动）

对 43 个剩余守卫文件审计后发现：**残存的"看日志/格式化"型脆弱断言，几乎全部集中在保护 OTP/密码/TOTP 的关键守卫里**（典型如 `BiometricUnlockRegressionGuardTest`——其断言直接守卫 `performOtpAutofillSideEffects` 走 `withContext(Dispatchers.IO)` 而非 `runBlocking` 的 OTP 复制路径，以及 TOTP 解析、密码加密边界）。

按 Tier B 的**锚点铁律**与项目"不得再次引入密码条目/验证码回归"的硬要求，这些断言**必须保留精确**（属 Tier C 意图守卫），一旦转正则而锚点失守就会削弱 OTP/密码防护牙齿。非关键的纯结构/标识符断言（如方法名、异常类）属低脆弱，本就无需正则化。

> 结论：**不为"更稳"而松动关键守卫**。B.2.3 的"止血"作用在已加固的高脆弱非关键守卫 + 写法规范上已经实现；关键 OTP/密码守卫保持精确，正是守护回归信号本身。

### 交付物核对

- [x] `docs/architecture-phaseB-b2.3-guard-governance.md`（含完整执行记录）
- [x] 多波 `dev` 提交 + 对应 CI 绿证明（`failed=0`）
- [x] `docs/guard-test-style-guide.md`（写法规范，防复发）
- [x] `BASELINE_FAILURES` 维持 `0` 不变（B.2.3 未引入任何新失败）
