# Bastion Phase B.3：PasswordViewModel 拆分执行计划

> **文档目的**：在 B.1/B.2.1/B.2.2/B.2.3 完成后，执行 B.3 —— 把 4162 行的 `PasswordViewModel.kt`
> 按职责簇拆分为多个协调器/工具类。**本文档即约定 #5 要求的"重点改动计划"，确认后逐步实施。**
>
> **创建时间**：2026-08-02
> **状态**：🟡 执行中（集群 1 ✅ / 2 ✅ / 4 ✅ / 5a ✅ / 5b ✅ / **6 ✅**；`PasswordViewModel` 4162 → 3700 行，
> 累计 -462；集群 6 已按 §7.7 完成抽取（18 个 mockk 行为测试护航，CI `total=559 failed=0`）；
> 剩集群 3/5c/7/8）
> **前置**：B.1 ✅、B.2.1 ✅、B.2.2 ✅、B.2.3 ✅（治理目标达成）
> **仓库**：https://github.com/Chaniug/bastion（dev 分支开发，验证后合并 main）
> **硬约束**：**不得引入密码条目 / 验证码（OTP/TOTP）回归**（用户明确要求）

---

## 一、当前状态

- **行数**：4162 行
- **函数数**：~140 个
- **职责簇**：7 个（见下表）
- **内部 new 的协作者**：9 个（未通过 DI 注入）

---

## 二、OTP / 密码防回归闸（每集群必达）

> 这是本项目最高优先级的约束。**每个集群拆分后必须全部满足，否则回退：**

1. `git push dev` 后 **CI `failed=0`**（编译 + 全量测试基线不上升）。
2. **关键守卫测试必须仍绿**（它们直接守卫 OTP/密码/TOTP 路径，任一变红即说明回归）：
   - `OtpAutofillClipboardRegressionGuardTest`（OTP 复制默认开 + 无障碍填充后留在剪贴板）
   - `BiometricUnlockRegressionGuardTest`（守卫 `performOtpAutofillSideEffects` 走 `withContext(Dispatchers.IO)` 而非 `runBlocking` 的 OTP 复制路径；密码加密/MDK 边界）
   - `TotpPasswordBindingRegressionGuardTest`、`PasswordTotpCrossDatabaseBindingGuardTest`（TOTP↔密码绑定）
3. **不改动任何被上述守卫 `substringAfter("fun xxx")` 抽取的函数体文本**（拆分只做"纯搬迁"或"构造注入"，不改既有逻辑与文案）。
4. 每完成一轮 → 真机（荣耀 / Android 17）抽查：**密码填充正确 + OTP 验证码进输入法剪贴板**。

> 任何守卫若因本次重构需"有意演进"，必须先在 B.2.3 治理纪律下改为**保留锚点的容错正则**或**行为测试**，禁止为过 CI 而删除/弱化守卫。

---

## 三、职责簇与拆分目标

| 职责簇 | 行范围 | 函数数 | 拆分目标 | 依赖 |
| --- | --- | --- | --- | --- |
| 顶层类型声明 | 82-149 | — | `CategoryFilter`、`PasswordArchiveFilterController`、`BitwardenRecoveryResult`、`BitwardenSyncRawHistoryItem` → 独立文件（同包） | 无（纯搬迁） |
| Bitwarden 离线缓存 | 240-1090 | ~25 | `BitwardenOfflineSecretCacheFacade` | bitwardenRepository, securityManager |
| KeePass 同步/对齐/TOTP 投影 | 1092-1727 | ~30 | `KeePassSyncCoordinator` | keepassBridge, repository |
| 类别过滤序列化/恢复 | 1728-1960 | ~12 | `CategoryFilterState` | settingsManager |
| 跨存储迁移 | 1961-2210 | ~14 | `PasswordMoveExecutor` | repository, keepassBridge, bitwardenRepository |
| Trash/批量删除 | 2599-2894 | ~14 | `PasswordDeleteOrchestrator` | repository, keepassBridge |
| 归档/取消归档 | 2895-3213 | ~22 | `PasswordArchiveOrchestrator` | repository, keepassBridge |
| 历史记录/自定义字段/主密码 | 3214-4162 | ~25 | `PasswordHistoryRecorder` + `MasterPasswordOps` | securityManager, passwordHistoryManager |

---

## 四、执行顺序（低风险 → 高敏感）

1. **集群 1（无状态类型搬迁）**：`CategoryFilter` + `PasswordArchiveFilterController` → `CategoryFilter.kt`（✅ CI 绿 `30725125903`）；续迁 `BitwardenRecoveryResult` + `BitwardenSyncRawHistoryItem` → `BitwardenSyncTypes.kt`（进行中）。**零逻辑变更**，最安全。
2. **集群 2（Bitwarden 离线缓存）**：抽 `BitwardenOfflineSecretCacheFacade`。
3. **集群 3（KeePass 同步协调器）**：抽 `KeePassSyncCoordinator`（含 TOTP 投影，敏感）。
4. **集群 4（类别过滤状态）**：抽 `CategoryFilterState`（序列化/恢复）。
5. **集群 5（跨存储迁移）**：抽 `PasswordMoveExecutor`。
6. **集群 6（删除/归档编排）**：抽 `PasswordDeleteOrchestrator` + `PasswordArchiveOrchestrator`。
7. **集群 7（主密码/历史）**：抽 `PasswordHistoryRecorder` + `MasterPasswordOps`（含 2 处 TODO 补全）。
8. **构造注入**：把 9 个内部 `new` 的协作者改为构造参数注入。
9. **每拆一簇推 CI + 真机抽查**，确保基线不上升、守卫仍绿。

---

## 五、注意事项

- `CategoryFilter` 密封类（13 分支）随首拆迁到 `CategoryFilter.kt`，全仓引用按"同包"解析，无需改 import。
- `BitwardenRecoveryResult`、`BitwardenSyncRawHistoryItem`、`KeePassCustomFieldFingerprint` 等私有类型，按簇移入各自协调器。
- `changePassword`(3572)、`saveSecurityQuestions`(3608) 含 TODO，拆分时一并补全。
- 所有 `private const`/`private data class` 若仅 VM 内部使用，留在 VM 直至对应簇抽取时一并迁移。

---

## 六、进度跟踪

| 集群 | 内容 | CI | 守卫(OTP/密码) | 真机 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 1 | 顶层类型搬迁（CategoryFilter+PasswordArchiveFilterController→CategoryFilter.kt；BitwardenRecoveryResult+BitwardenSyncRawHistoryItem→BitwardenSyncTypes.kt） | ✅ 30725125903+30725511081 | ✅ 0 失败 | — | ✅ 完成 |
| 2 | Bitwarden 离线缓存 → `BitwardenOfflineSecretCacheFacade` | ✅ 30726186131 | ✅ 0 失败 | ✅ 通过 | ✅ 完成 |
| 3 | KeePass 同步协调器 | — | — | — | ⬜ **延后**（见 §七） |
| 4 | 类别过滤解码 → `CategoryFilterCodec` | ✅ 30726656548 | ✅ 0 失败 | ✅ 通过 | ✅ 完成 |
| 5a | 匹配/去重纯函数 → `PasswordEntryMatching`（10 个函数） | ✅ 30727252608 | ✅ 0 失败 | ✅ 通过（build.202608020221 / 44e0657f）| ✅ 完成 |
| 5b | `applyCategoryFilterInMemory` 并入 `PasswordEntryMatching` | ✅ 30727505041 | ✅ 0 失败 | ✅ 通过（build.202608020221 / 44e0657f）| ✅ 完成 |
| 5c | 跨存储迁移 `move*` → `PasswordMoveExecutor` | — | — | — | ⬜ 有状态，见 §7.4；**待补行为测试** |
| 6 | 删除/归档编排 | ✅ 30728150825 + 30728548671 + 30729317779（`total=559 failed=0`） | ✅ 0 失败 | ✅ 通过（build.202608020221 / 44e0657f + 用户复核）| ✅ 完成（见 §7.7） |
| 7 | 主密码/历史 | — | — | — | ⬜ **待补行为测试** |
| 8 | 构造注入 | — | — | — | ⬜ 见 §7.6（只有 3/9 可行） |

---

## 七、执行中的顺序调整与踩坑记录（接力 agent 必读）

### 7.1 集群 3（KeePass 同步协调器）为何延后

原计划顺序是 1→2→3，实际执行时把 **集群 4 提前到集群 3 之前**，理由：

- `PasswordViewModel.kt` 中 KeePass 相关引用达 **359 处**，远超集群 2（8 处）与集群 4（33 处）。
- 集群 3 范围内含 **TOTP 投影**（`KeePassTotpProjectionMatcher` 相关路径），
  直接触碰用户明令不得回归的验证码链路。
- 当前守卫对 KeePass↔TOTP 投影的覆盖是「源码文本断言」而非行为测试，
  重构过程中**守卫无法真正兜住语义回归**，只能兜住文本不变。

**结论**：集群 3 应在两个前置条件满足后再动 ——
（a）为 TOTP 投影补一组**行为测试**（Tier A），不再只依赖文本断言；
（b）用户完成一次针对 KeePass 库 + TOTP 的真机专项抽查，确立可信基线。
在此之前优先推进低风险集群（5/6/7 中的无状态部分）。

### 7.2 守卫陷阱：`substringAfter` 抽取型断言（集群 4 实测）

`PasswordArchiveReturnFilterGuardTest` 的断言方式是：

```kotlin
val persistence = source.substringAfter("private fun persistCategoryFilter(")
val archivedBranch = persistence.substringAfter("is CategoryFilter.Archived ->")
    .substringBefore("is CategoryFilter.Local ->")
assertFalse(archivedBranch.contains("updateLastPasswordCategoryFilter"))
```

**陷阱**：Kotlin 的 `substringAfter` 在**找不到分隔符时返回原字符串**（而非空串）。
因此若把 `persistCategoryFilter` 整个搬出 `PasswordViewModel.kt`，
`persistence` 会退化为**整个源文件**，`archivedBranch` 随之覆盖大段无关代码，
断言会以一种**看似合理实则失真**的方式变红或变绿——两种都是错误信号。

**处置**（本次采用）：集群 4 只搬 **decode 侧**（纯函数、无锚点依赖），
`persistCategoryFilter` **刻意留在 VM 内**保住锚点；17 个 `SAVED_FILTER_*`
字面量的唯一真源移入 `CategoryFilterCodec`，VM 内保留同名 `const` 别名，
使 persist 分支的**文本形态完全不变**。

**通用规则**：搬迁任何函数前，先 `grep -rn "substringAfter(\"...fun 目标函数" app/src/test`，
命中即说明该函数是**守卫锚点**，只能原地保留或先将守卫升级为 Tier A 行为测试。

### 7.3 推送网络：GitHub 直连 IP 会**逐 IP、逐端点**失效

实测现象：同一时刻 `140.82.113.3` 对 `https://github.com/...`（网页/克隆）返回 **200**，
但对 `.../info/refs?service=git-receive-pack`（**推送**端点）返回 **000**（连接被中断）。
即「能拉不能推」，仅测 curl 首页会误判为网络正常。

**正确探测方式**（判定某 IP 是否可推送）：

```bash
curl -s -o /dev/null -w "%{http_code}" --resolve github.com:443:<IP> \
  -u "x-access-token:$(gh auth token)" \
  "https://github.com/Chaniug/bastion.git/info/refs?service=git-receive-pack"
# 200 = 可推送；000 = 该 IP 推送端点不通，换 IP
```

**候选 IP**：`140.82.112.3` / `140.82.113.3` / `140.82.114.3` / `140.82.116.3` / `20.205.243.166`
（`api.github.com` 用 `140.82.113.5`）。修改 `/etc/hosts` 后**必须同步写入 `~/.user_hosts`**，
否则工作区重启会被还原。本次多轮推送中可用 IP 从 `.113.3` 漂移到 `.114.3` 再到 `.112.3`，
建议接力 agent 直接写一个「探测→切 hosts→重试」的循环脚本，不要手工试。

### 7.4 集群 5 的实际拆法：先纯函数（5a/5b），`move*` 编排（5c）留后

原计划集群 5 是「跨存储迁移 → `PasswordMoveExecutor`」。实际执行时先做了
**5a/5b（纯函数抽取）**，把 `move*` 编排留作 5c，理由：

对 VM 做过一轮**纯度扫描**（判据：函数体不出现 repository / StateFlow /
viewModelScope / securityManager / keepass / bitwarden / Log / context 等标记），
177 个顶层函数中有 42 个是纯函数，合计约 292 行。其中「文本规范化 + 去重键 +
匹配判定 + 内存筛选」这一簇高度内聚且零依赖，抽出来**收益确定、风险接近于零**，
应当优先做完。

相比之下 `move*` 簇（5c）依赖 repository、两个 KeePass executor、
bitwardenRepository、`decodePasswordOrNull`、以及附件本地化门禁
（`materializeMovedKeePassAttachments` 失败即阻断源删除），属于真正的有状态编排，
必须走构造注入而非搬迁，风险等级与集群 6/7 相当。

**已确认**：`move*` 全簇（`movePasswordsToCategory` / `...ToKeePassDatabase` /
`...ToKeePassGroup` / `...ToBitwardenFolder` / `movePasswordsToKeePassInternal` /
`deleteMovedKeePassPasswordSources` / `materializeMovedKeePassAttachments`）
**未被任何守卫测试引用**，抽取时不受锚点约束——但这也意味着**没有回归网**，
5c 落地前应先补一组行为测试。

### 7.5 已知重复实现：`matchesSearchQuery` 有两份，刻意不合并

| 位置 | 是否 trim 查询串 | 比对字段 |
| --- | --- | --- |
| `PasswordEntryMatching.matchesSearchQuery` | ✅ trim | title / website / username / appName / **appPackageName** |
| `AutofillPickerActivityV2.kt:3741`（私有扩展） | ❌ 不 trim | title / appName / username / website |

两者命中集合**不同**：自动填充侧不比对包名、且保留查询串首尾空格。
合并任何一方都会静默改变自动填充的候选列表——属于用户明令不得回归的填充路径。
若要统一，必须**先补自动填充搜索的行为测试**，再以测试为准绳收敛。
当前已在 `PasswordEntryMatching` 类注释中标注该差异。

### 7.6 纯函数缝已挖尽 + 集群 8 只有 1/3 可行（2026-08-02 复扫结论）

#### （a）纯函数抽取到此为止

集群 5a/5b 后重跑纯度扫描（脚本判据同 §7.4）：177 个顶层函数中「纯」的还剩 31 个、
共 223 行——但其中绝大多数是 **5a/5b 留下的委托桩**（3 行一个），
真正未抽的新料仅约 60 行，且彼此毫无内聚
（`copyPasswordToBastionLocal` / `quickAddPassword` / `buildTotpCopyIdentityKey` / `parseStoredTotpData` …）。

**结论**：强行凑成一个新文件只会造出杂物抽屉，可读性反而更差。
B.3 后续必须转向**有状态编排的抽取**，而那要求先有行为测试网 —— 这正是引入 mockk 的直接动因。

#### （b）集群 8「9 个协作者构造注入」只有 3 个可行

原计划写的是「把 9 个内部 `new` 的协作者改为构造参数注入」，实测**其中 6 个做不到**：

| 协作者 | 初始化表达式依赖 | 可否构造注入 |
| --- | --- | --- |
| `PasswordCommandStateFactory` | 无依赖 | ✅ 可 |
| `PasswordArchiveFilterController` | 无依赖 | ✅ 可 |
| `BitwardenSyncSnapshotPreviewParser` | 无依赖 | ✅ 可 |
| `keepassBridge` | `context` + `localKeePassDatabaseDao` + `securityManager` | ❌ |
| `keepassPassword{Delete,Create,Update}Executor` | `keepassBridge`（实例属性）| ❌ |
| `defaultPasswordProvider` | `::decodePasswordOrNull`（实例方法引用）| ❌ |
| `passwordProviderRegistry` | 同上 + `securityManager::encryptData` | ❌ |

**根因**：Kotlin 构造参数的默认值只能引用**前序构造参数**，不能引用实例属性或
`this` 的方法引用。`private val x = Foo(::instanceMethod)` 这类初始化式一旦挪进构造签名就编译不过。

**可行改法**（若将来要做）：把默认值改为**工厂 lambda 参数**，例如
`providerRegistryFactory: (PasswordViewModel) -> PasswordProviderRegistry = { ... }`，
或引入真正的 DI（Hilt）。二者都属于跨越 B.3 范围的架构改动，应单列任务，不要塞进集群 8。

#### （c）测试基建现状（决定后续所有集群的可行性）

| 项 | 现状 |
| --- | --- |
| 测试文件总数 | 139 |
| 其中读源码做**文本断言**的 | 53（`*RegressionGuardTest` 主力） |
| 引入 mockk 前的 mock 框架 | **无**（无 mockk / Mockito / Robolectric）|
| `PasswordRepository` | Kotlin **final class**，79 个公开方法（非接口）|
| `SecurityManager` | Kotlin **final class**（非接口）|

即：在引入 mockk 之前，**物理上写不出**编排类的行为测试——既不能继承造 Fake，也不能 mock。
这就是集群 3/5c/6/7 全部零覆盖的根因，而非疏忽。

### 7.7 集群 6 的抽取做法：行为测试网 → 构造注入 → 薄委托（2026-08-02）

集群 6（删除/归档编排）是本项目第一个**先补行为测试、再动手抽取**的集群，流程如下：

1. **Step 0/1（mockk 基建 + 行为测试）**：引入 mockk **1.13.17**（版本选择见
   `phaseB3-mockk-behavior-tests-plan.md`，1.14.x 与项目 Kotlin 2.0.21 元数据不兼容），
   建成 18 个行为测试（`PasswordDeleteBehaviorTest` 6 个 + `PasswordArchiveBehaviorTest` 9 个 +
   `MockkInfrastructureSmokeTest` 3 个），全部走 `context = null` 夹具
   （VM 协作者退化为 null，删除路径坍缩为纯本地分支，断言目标唯一）。
   CI `total=559 failed=0`（run 30728548671）。
2. **抽取**：`PasswordArchiveOrchestrator`（256 行，9 个函数）逐字节搬迁，业务逻辑零改动，
   用脚本对比搬迁前后函数体确认**逐字节等价**（仅机械重命名：
   `commandPolicyOf(entry)`、`stateFactory.create`、`ensureKeePassArchiveGroupPath` 等）。
   CI `total=559 failed=0`（run 30729317779）。
3. **KeePass 侧用函数引用注入而非整体搬走**：`ensureArchiveGroupPath` /
   `resolveRestorePathOrRoot` / `moveEntryGroupPath` 三者依赖 `keepassBridge`（组合构造：
   context + DAO + securityManager）且内部复用带解密副作用的
   `resolvePlainPasswordForKeePass` / `resolveKeePassCustomFieldsForSync` —— 整体搬走会牵动
   KeePass 与 TOTP 投影链路（集群 3 范围，用户明令不得回归）。故只注入函数引用，
   **实现继续留在 VM**。该模式与集群 2 `BitwardenOfflineSecretCacheFacade` 一致（已验证）。
4. **守卫锚点零破坏**：`openArchiveView` / `closeArchiveView` / `archivedPasswordsForUi` /
   `persistCategoryFilter` 等锚点函数全部留在 VM，未触碰任何 `substringAfter` 抽取型断言。

**遗留**：`context = null` 夹具只能覆盖本地（PROVIDER_LOCAL）分支，KeePass/Bitwarden
路径的行为测试仍缺 —— 与集群 3/8 的解耦（factory lambda 或 Hilt）绑定，
需真机专项抽查（荣耀 / Android 17，KeePass 库条目归档/取消归档）。

### 7.8 主页面滚动卡顿修复：方案 A（TOTP 滚动降频，2026-08-02）

**用户反馈**：Bitwarden 库同步后，主页面下划滚动密码条目卡顿、不跟手。

**根因**（全链路排查结论）：

| 环节 | 位置 | 事实 |
| --- | --- | --- |
| 全局 ticker | `ui/totp/TotpTicker.kt:16` | 平滑模式**每 50ms** 向全局 `StateFlow` emit |
| 卡片订阅 | `ui/password/PasswordCardDisplayContent.kt:91` | 每个可见 TOTP 卡每 50ms 重组（code+进度全重算）|
| 默认开关 | `data/AppSettings.kt:514/540` | `validatorSmoothProgress=true`、`passwordCardShowAuthenticator=true` 默认开 |
| Bitwarden 落库 | `bitwarden/service/CipherSyncProcessor.kt:350` | 同步时条目带 `login.totp` 即加密写入 `authenticatorKey` → Bitwarden 库条目普遍带 TOTP |

即：一屏常驻多张验证码卡片，每 50ms 全部重组 → 滚动时主线程被重组抢占 → 掉帧。

**方案 A 修复**（用户确认；纯 UI，未触碰业务/守卫，3 文件 +17/-6）：

1. `PasswordListContent.kt`：`val isListScrolling by remember { derivedStateOf { listState.isScrollInProgress } }`
   —— `isScrollInProgress` 只在滚动开始/结束翻转，derivedStateOf 保证不会每帧触发重组。
2. `PasswordListRows.kt`：`passwordPageListRows` 新增 `isListScrolling` 参数，三处
   `smoothAuthenticatorProgress = ... && !isListScrolling` —— 滚动中验证码行从 50ms
   平滑刷新**降为秒级刷新**（复用既有 `secondTicker` 路径，`smooth=false` 本就是设置里的
   既有行为），松手恢复平滑进度条。
3. `StackedPasswordGroup.kt`：`isMergedPasswordCard` 的 `getPasswordInfoKey` 组内计算
   用 `remember(passwords)` 缓存，避免每帧 O(n) 字符串拼接（Bitwarden 条目多时明显）。

**设计要点**：所有 TOTP 卡渲染入口（分组堆叠 / 手动堆叠 / supplementary 聚合卡）都汇聚到
`PasswordListRows.kt` 三处，一处降频全链路生效；`rememberPasswordAuthenticatorDisplayState`
的 `smoothProgress=false` 分支是既有代码（设置里关闭平滑即走此路径），零新增刷新机制。

**验证**：CI `failed=0` + 7 守卫绿（守卫不引用这些文件）；**真机 ✅ 通过（2026-08-02）**：
荣耀 / Android 17 实测 Bitwarden 库滚动流畅，密码填充 / 归档 / OTP·TOTP 验证码均无异常。
