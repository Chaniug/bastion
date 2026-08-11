# Bastion vs Bitwarden 自动填充判定机制对比与改造计划

> 对比对象：[bitwarden/android](https://github.com/bitwarden/android) `app/src/main/kotlin/com/x8bit/bitwarden/data/autofill/`
> 对比时间：2026-08-10
> 结论：**Bitwarden 的准确性确实更好，但原因不是算法更聪明，而是架构哲学不同。**

> **改造进度**（2026-08-11 更新）
> - ✅ **P0 已完成并合并**：dev `a97d7013` / main `16a8fbd5`，CI 双绿；预览版真机回归（京东/Via/电影猎手/淘宝/微信/支付宝）无回归
> - ✅ **P1 已完成并合并**：dev `229bffe3` / main `5c0cf18b`，CI 双绿；判定链路从 4 层降到 2 层
> - ✅ **P2 已完成并合并**：dev `01b4027c` / main `322ca2a9`，CI 双绿（单测 21 例）；预览包 `build.202608110000` 已发布
> - ⏳ P3 待后续评估

---

## 一、结论先行

Bitwarden 更准，根源在于一句话：

> **Bitwarden 严格区分「这不是登录框」和「这可能是登录框但我不确定」；Bastion 把两者混成了同一个东西——低精度分数。**

Bitwarden 的字段分类是**三值离散**的：`Login.{Username, Password, Email}` / `Card.*` / **`Unused`**。
`Unused` 的语义是明确的**否定**：这是个输入框，但它不是我要填的东西。它**不参与**任何弹出决策。

Bastion 的字段分类是**连续精度打分**：`LOWEST(0.3) → LOW(0.7) → MEDIUM(1.5) → HIGH(4) → HIGHEST(10)`。
一个识别不出来的 WebView 文本框会被兜底成 `USERNAME:LOWEST` —— 它的语义是**弱肯定**：「这是个用户名框，只是我不太确定」。

这个语义差别是致命的。因为下游任何一个「放宽条件」的分支（`allowWeakTargets`、`weakLoginContext`、手动请求…）都会把这个「我不知道」当成「一个置信度低的用户名框」放行。京东搜索栏误弹，本质就是这么来的。

**一个佐证**：`InternalHint.UNKNOWN` 在 `EnhancedAutofillStructureParserV2.kt` 中**全文只出现 1 次**——枚举定义处。它从未被真正使用过。Bastion 的类型系统里有「未知」这个词，但架构里没有「未知」这个概念。

---

## 二、架构对比

| 维度 | Bitwarden | Bastion | 优劣 |
|---|---|---|---|
| **字段分类** | 三值离散：Login / Card / **Unused** | 连续精度 0.3~10 + 30+ hint 类型 | ✅ BW：语义无歧义 |
| **账号框判定** | 白名单：`inputType == WEB_EMAIL_ADDRESS` 或 idEntry/hint 含 `email`/`phone`/`username` 或 htmlInfo | 多信号加权 + `WEB_EDIT_TEXT` 兜底 `USERNAME:LOWEST` + 术语 `MEDIUM` | ✅ BW：宁漏不误 |
| **无信号文本框** | → `Unused` → 过滤 → `Unfillable`（**不弹**） | → 可能 `USERNAME:LOWEST` → 弱解析放行（**弹**） | ✅ BW |
| **弹出决策** | 极简：过滤 Unused 后非空 → Fillable | 层层设卡：精度阈值 + `weakLoginContext` + `shouldKeepTarget` + 服务层守卫 | ✅ BW：可推理 |
| **密码框兜底** | 无密码框时，把含 password 术语的 Unused 提为 Password | `promotePasswordTermCandidates`（已对齐） | 🟰 持平 |
| **账号框兜底** | **有密码框但无账号框时，取密码框紧邻上方那一个 Unused 提为 Username** | **无此机制**（靠 `WEB_EDIT_TEXT` 兜底 + 弱解析全量放行代偿） | ✅ BW：精准补召回 |
| **焦点处理** | 优先取焦点所在 window 的 views；该集合无 fillable → 直接 Unfillable | 有焦点信息但不作硬门槛 | ✅ BW |
| **填充阶段校验** | 逐字段校验 `data.website == cipher.website` 或 `androidapp://pkg == cipher.website`，无可填项的 partition 丢弃 | `allowPackageMatching` 对 native 包直接按包名匹配 | ✅ BW：双向校验 |
| **疑难页面** | 服务端下发 per-host `FillAssist` 规则（feature flag 控制） | 本地启发式加权 | ✅ BW：可热修 |
| **搜索框识别** | **不需要专门识别**——白名单天然排除 | 专门的 `isSearchField()`（80 行多信号识别） | ✅ BW：无需此模块 |
| **hint 覆盖面** | 仅 8 个系统 hint + 少量术语 | 30+ hint 类型、多语言术语表 | ✅ Bastion：场景更广 |

---

## 三、关键代码级差异

### 3.1 账号框判定：白名单 vs 兜底

**Bitwarden**（`util/ViewNodeExtensions.kt:289`、`util/IntExtensions.kt:25`）：

```kotlin
internal val AssistStructure.ViewNode.isUsernameField: Boolean
    get() = inputType.isUsernameInputType ||                                  // 仅 WEB_EMAIL_ADDRESS
        idEntry?.containsAnyTerms(SUPPORTED_RAW_USERNAME_HINTS) == true ||    // email/phone/username
        hint?.containsAnyTerms(SUPPORTED_RAW_USERNAME_HINTS) == true ||
        htmlInfo.isUsernameField()

val Int.isUsernameInputType: Boolean
    get() = this.hasFlag(InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)     // 只此一种
```

术语表极小（`util/ViewStructureUtils.kt:24`）：`["email", "phone", "username"]`。
匹配不上 → `AutofillView.Unused` → 后续被 `it !is AutofillView.Unused` 过滤掉。

**Bastion**（`EnhancedAutofillStructureParserV2.kt:1594`）：

```kotlin
inputIsVariationType(inputType, TYPE_TEXT_VARIATION_NORMAL, ..., TYPE_TEXT_VARIATION_WEB_EDIT_TEXT) -> {
    // 纯 text 变体已对齐 bitwarden（不再兜底）
    if (inputIsVariationType(inputType, TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)) {
        out += ParsedItemBuilder(accuracy = Accuracy.LOWEST, hint = InternalHint.USERNAME)  // ← 误弹源头
    }
    ...
}
```

`TYPE_TEXT_VARIATION_NORMAL` 的兜底已经删掉了（QQ 搜索框修复），但 `WEB_EDIT_TEXT` 的兜底还在。京东搜索栏若跑在 WebView 里，就走这条路。

### 3.2 账号框召回：结构化邻居 vs 全量放行

**Bitwarden**（`parser/AutofillParserImpl.kt:335`）：

```kotlin
private fun ViewNodeTraversalData.updateForMissingUsernameFields(): ViewNodeTraversalData {
    val passwordPositions = autofillViews.mapIndexedNotNull { i, v ->
        (v as? AutofillView.Login.Password)?.let { i }
    }
    return if (passwordPositions.any() && autofillViews.none { it is AutofillView.Login.Username }) {
        copyAndMapAutofillViews { index, view ->
            // 只提升「紧邻密码框上方」的那一个 Unused
            if (view is AutofillView.Unused && passwordPositions.contains(index + 1)) {
                AutofillView.Login.Username(data = view.data)
            } else view
        }
    } else this
}
```

三重约束：① 必须存在密码框；② 必须尚无账号框；③ **只取密码框上方紧邻的那一个**。
精准、可预测、不会波及搜索栏（搜索栏旁边没有密码框）。

**Bastion**（`EnhancedAutofillStructureParserV2.kt:637`）：

```kotlin
val weakLoginContext = allowWeakTargets && !hasPasswordInItems && hasLoginTypeField
val loginFilteredItems = when {
    hasPasswordInItems -> effectiveItems
    weakLoginContext  -> effectiveItems          // ← 无密码框时全量放行，无位置约束
    else -> effectiveItems.filterNot { 账号类 && accuracy < MEDIUM }
}
```

代码注释里写着「对齐 bitwarden 的『有 Login.Username 就 Fillable』逻辑」——**这是一处误读**。
Bitwarden 确实在决策层不做二次过滤，但那是因为它在**识别层**已经把搜索框判成 `Unused` 了。Bastion 识别层放得更宽，却照搬了决策层的宽松，两头都松，误弹就出来了。

### 3.3 填充阶段：双向校验 vs 单向包名匹配

**Bitwarden**（`builder/FilledDataBuilderImpl.kt:150`）：

```kotlin
val filledItems = autofillViews.mapNotNull { autofillView ->
    if (autofillView.data.website == autofillCipher.website ||
        buildUri(packageName.orEmpty(), "androidapp") == autofillCipher.website) {
        ...                                     // 字段 website 必须与条目 website 一致
    } else null
}
// 再过滤掉没有任何可填项的条目
filledPartitions.filter { it.filledItems.isNotEmpty() }
```

**Bastion**（`AutofillRequestContextPolicy.kt:39`）：

```kotlin
fun allowPackageMatching(packageName: String, webDomain: String?, isWebView: Boolean): Boolean {
    if (!webDomain.isNullOrBlank()) return true
    if (isWebView) return false
    return packageName.trim().lowercase() !in knownBrowserPackages   // native 包一律 true
}
```

native 包直接放行按包名匹配，缺少 Bitwarden 那层「字段维度的 website 一致性」复核。

### 3.4 Bitwarden 的「逃生舱」：服务端规则

`AutofillParserImpl.toEffectiveViews()` 会按当前页面 host 查询 `fillAssistManager.getFillAssistRules()`，命中则**用服务端规则完全接管**该 partition 的字段选择（含 `account-login` / `payment-card` 等 category）。

这意味着 Bitwarden 遇到疑难站点时，**不需要改本地启发式、不需要发版**——下发一条 host 规则即可。这是它敢把本地启发式做得如此保守的底气。

---

## 四、Bastion 现有设计的代价

翻一下修复史就能看出模式：

| 问题 | 修法 | 副作用 |
|---|---|---|
| QQ 搜索框误弹 | 删掉 `TYPE_TEXT_VARIATION_NORMAL → USERNAME:LOWEST` 兜底 | — |
| Via 浏览器密码框填不进 | 加 `promotePasswordTermCandidates` 无条件执行 | — |
| 电影猎手漏弹 | 加 `weakLoginContext` 全量放行 | **埋下京东误弹** |
| 京东搜索栏误弹 | 加服务层守卫（v1.0.301） | 给 `weakLoginContext` 打补丁 |

**每修一个 case 就加一层卡**，卡与卡之间是全局阈值，互相牵制。这就是「修了误弹又出漏弹」的结构性来源。目前 `processFillRequest` 到弹出决策之间已经有 4 层判定，任何人（包括后来的 agent）都很难在改动前推理出全部影响面。

---

## 五、改造计划

设计目标：**把判定从「决策层层层设卡」搬回「识别层一次判准」**，向 Bitwarden 的架构收敛，同时保留 Bastion 在 hint 覆盖面上的优势。

### 阶段一（P0）：引入 UNKNOWN 语义，切断「未知 → 弱 USERNAME」 ✅ 已完成

> 2026-08-11 落地：dev `a97d7013` / main `16a8fbd5`（CI 双绿），预览版真机回归通过。
> 改动：`WEB_EDIT_TEXT` 兜底改判 `InternalHint.UNKNOWN`；新增 `AutofillFieldPromotionPolicy`（纯 JVM 单测 9 例）+ `promoteUsernameNeighborCandidates` 接入解析管道；诊断日志扩展；同步更新 `AutofillDetectionIntegrationGuardTest`。

**改动**
1. `EnhancedAutofillStructureParserV2.kt:1607` —— `WEB_EDIT_TEXT` 不再兜底为 `USERNAME:LOWEST`，改为产出 `InternalHint.UNKNOWN`（复活这个从未被使用的枚举值）。
2. 新增 `promoteUsernameNeighborCandidates()`，对齐 Bitwarden `updateForMissingUsernameFields`：
   - 前提：已存在密码框 且 尚无账号框；
   - 动作：把**紧邻密码框上方**的那一个 `UNKNOWN` 提升为 `USERNAME:LOW`；
   - 位置：紧随 `promotePasswordTermCandidates` 之后调用。
3. `UNKNOWN` 字段不进入 `fillableTargets`，但保留在 `ignoreAutofillIds` 里（与 Bitwarden 一致，避免系统重复请求）。

**为什么这一条同时解决误弹和漏弹**

| 场景 | 结构特征 | 改造后行为 |
|---|---|---|
| 京东搜索栏 | 孤立 WEB_EDIT_TEXT，无密码框 | `UNKNOWN` → 不弹 ✅ |
| Via 浏览器登录页 | WEB_EDIT_TEXT 账号框 + 密码框相邻 | 邻居提升 → 正常弹 ✅ |
| 电影猎手登录页 | 账号框 + 密码框 | 密码框在 → 正常弹 ✅ |
| 标准系统 hint 页面 | `AUTOFILL_HINT_USERNAME` | 走 HIGH 精度，不受影响 ✅ |

**风险**：中。动到解析核心。缓解：邻居提升覆盖了绝大多数 WebView 登录页；上线前用现有 autofill 测试套件 + 真机回归（京东搜索 / 京东登录 / Via / 电影猎手 / 淘宝 / 微信）。

### 阶段二（P1）：拆掉 `weakLoginContext` 与服务层守卫 ✅ 已完成

> 2026-08-11 落地：dev `229bffe3` / main `5c0cf18b`（CI 双绿）。
> 改动：删除 `weakLoginContext` 分支与 `hasLoginTypeField`（死代码）；新增
> `AutofillDetectionPolicy.shouldKeepLoginField`（识别层保留规则单一来源）；删除
> `shouldSuppressWeakLoginSuggestion` 及其调用点；`WeakLoginGuardTest` 重写为
> `LoginFieldRecognitionTest`（纯 JVM 13 例验证识别层行为）。

阶段一落地后，`weakLoginContext` 的全量放行和 v1.0.301 的服务层守卫都成了冗余补丁——前者要放行的场景已由「有密码框 → 全量放行」与邻居提升精准覆盖，后者要拦截的场景已在识别层消失（京东搜索栏已判为 UNKNOWN，不再产出 Login 字段）。

**改动清单**
1. `EnhancedAutofillStructureParserV2.kt:647` —— 删除 `weakLoginContext` 变量及其 `when` 分支，
   `loginFilteredItems` 简化为 `if (hasPasswordInItems) effectiveItems else filterNot { 账号类 && accuracy < MEDIUM }`；
   同步删除仅被它消费的 `hasLoginTypeField`（死代码）与日志字段；
2. `AutofillDetectionPolicy.shouldSuppressWeakLoginSuggestion()` 及其在 `BastionAutofillServiceNg` 的调用点整体删除；
3. 测试：`WeakLoginGuardTest` 重写为**识别层行为验证**（无密码框的弱账号字段不再进入 login 目标）；
   `AutofillDetectionIntegrationGuardTest` 中 `weakLoginContext`/`hasLoginTypeField` 断言改为 `assertFalse`。

**行为变化（预期）**
- 京东搜索栏：UNKNOWN → 识别层即消失，守卫不再被需要 ✅
- 电影猎手等原生登录页：有密码框 → `hasPasswordInItems` 分支全量放行，不受影响 ✅
- 无密码框 + 仅弱账号字段（`USERNAME:LOWEST`，如 `idType="text"`）：不再被弱模式全量放行，
  自动/手动请求均不弹——与 Bitwarden「未识别字段 = Unused → 不弹」一致 ✅
- 无密码框 + `MEDIUM` 账号字段（native `id="username"` 术语）：仍弹——对齐 Bitwarden
  「识别为 Login.Username → Fillable」（原守卫以 HIGH 为门槛属过度收紧）⚠️ 需真机回归关注

**收益**：判定链路从 4 层降到 2 层，后续 agent 接手时可推理性大幅提升。
**风险**：低（阶段一已覆盖），**但必须在阶段一真机验证通过后再做**——已满足。

### 阶段三（P2）：填充阶段补 website 双向校验 ✅ 已完成

> 2026-08-11 落地：dev `01b4027c` / main `322ca2a9`（CI 双绿）。
> 改动：新增 `AutofillWebsiteConsistencyPolicy`（纯 JVM 单测 21 例）；`BastionAutofillServiceNg`
> 在 matcher 结果之后、`stabilizeMatchedPasswords` 之前加入一致性过滤（唯一插入点，覆盖
> Picker V2/legacy/直填/密码优先全部路径）；过滤时输出 `P2 website-consistency filtered entries`
> 诊断日志。

对齐 `FilledDataBuilderImpl.fillLoginPartition`：构建 dataset 前对**条目**做 website 一致性校验，丢弃「明确绑定其它站点/其它 App」的条目。

**落地设计（2026-08-11，用户确认「仅拒绝明确矛盾」版）**

Bitwarden 是「严格相等」（`data.website == cipher.website` 或 `androidapp://pkg == cipher.website`），
照搬会让大量未填 website/appPackageName 的 KeePass 裸条目漏填。因此采用宽松判定：

| 页面类型 | 判定轴 | 规则 |
|---|---|---|
| web（pageWebDomain 非空） | 注册域 | 条目含 web 域名时，任一域名注册域与页面一致即放行（支持子域名/www/端口/多 URI/大小写）；全部不一致 → 拒绝；无 web 域名 → 放行（页面包名是浏览器包名，无判定意义） |
| 原生（pageWebDomain 为空） | 包名 | 条目 appPackageName 或 androidapp:// 包名任一等于当前包名即放行；全部不一致 → 拒绝；无包名 → 放行（Keepass 约定：原生登录也存服务官网域名） |
| 无 website 且无包名 | — | 放行（KeePass 裸条目 / WiFi 条目） |

**落地位置**：`BastionAutofillServiceNg` matcher 结果之后、`stabilizeMatchedPasswords` 之前（唯一插入点，
覆盖 Picker V2/legacy/直填/密码优先全部路径）；策略抽为 `AutofillWebsiteConsistencyPolicy`（纯 JVM 单测）。

**收益**：即使识别层偶有误判（非严格模式启发式匹配 / native 包名 token 匹配），明确矛盾的条目也不会被填充。
这是 Bitwarden 的**第二道防线**。
**风险**：低（仅拒绝明确矛盾，无 URI 条目不受影响）；需真机回归关注淘宝/微信/支付宝等 native 登录。

### 阶段四（P3，长期）：本地 per-package/per-host 规则表

Bitwarden 的 FillAssist 依赖服务端，Bastion 无后端，但可以做**内置规则表**（`assets/autofill_rules.json`），随版本更新：

```json
{
  "com.jingdong.app.mall": { "ignoreIdEntries": ["search_edit_text", "search_box"] },
  "login.taobao.com":      { "usernameIdEntries": ["fm-login-id"], "passwordIdEntries": ["fm-login-password"] }
}
```

**收益**：高频 App 的疑难场景可以精确定点修复，不再需要动全局启发式。
**风险**：低（纯增量），工作量中等。

---

## 六、建议的执行顺序

```
阶段一（P0）─→ 真机回归 ─→ 阶段二（P1）─→ 真机回归 ─→ 阶段三（P2）─→ 阶段四（P3）
   核心           必须            清理           必须          加固         长期
```

阶段一与阶段二**不要合并发版**。阶段一是行为变更，需要真机确认召回没有退化；阶段二是纯删除，必须建立在阶段一被验证之后。

---

## 七、客观地说：Bastion 哪些地方比 Bitwarden 强

避免一边倒，这些是不该在改造中丢掉的：

1. **hint 覆盖面**：Bastion 支持 30+ 字段类型（身份证、公司名、地址细分、OTP），Bitwarden 只有登录 + 银行卡。
2. **多语言术语表**：Bastion 有中文术语识别（`用户名`/`密码`/`账号`），Bitwarden 只有英文术语，中文 App 上原生 Bitwarden 的召回其实不如 Bastion。
3. **诊断能力**：`AutofillDiagnostics.kt`（817 行）+ 结构化日志，排障体验优于 Bitwarden 的 Timber 打点。
4. **容器级搜索识别**：`isSearchContainerNode` 能识别搜索容器，Bitwarden 无对应能力。

**改造的目标是换掉判定架构，不是换掉这些能力。** 术语表和 hint 类型应当原样保留，只是让它们输出「明确的肯定」或「明确的未知」，而不是「弱肯定」。
