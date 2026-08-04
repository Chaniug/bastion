# Bastion 架构升级 Phase C：运行时性能优化

> **文档目的**：针对 Bastion App 的运行时性能问题进行系统性优化，供多 agent 接力开发。
>
> **创建时间**：2026-08-01
> **状态**：Agent 1 批次（C.1 + C.4.1 + C.5）已实施并通过 CI ✅；C.2 / C.3 / C.4.2-4 / C.6 待维护者确认计划后推进
> **前置条件**：Phase A ✅ 已完成并合入 main（`69c9f8b5`）
> **与 Phase B 的关系**：Phase B 关注代码可维护性，Phase C 关注运行时性能。两者可并行推进，互不依赖。
> **仓库**：https://github.com/Chaniug/bastion（dev 分支开发，验证后合并 main）
> **真机测试**：荣耀 Android 17

---

## 零、进度记录（dev 分支）

| 批次 | 任务 | 提交 | CI（Android CI debug） | 状态 |
| --- | --- | --- | --- | --- |
| Agent 1（低风险） | C.1 主线程 IO + C.4.1 SupervisorJob + C.5 LazyColumn key | 初推 `e4e3e1f5`/`66c2e2b4`/`d43f2950`；修复 `6bc9d132` | #289 失败 → #290 **Success** ✅ | 已完成并通过 CI |

### 关键修正（提交 `6bc9d132`）
- **C.5 `key` lambda 参数易错点**：`items(...)` 的 `key` lambda 参数是「列表项本身」，应写 `it` 或显式参数名（如 `row ->`），**不能**用内容 lambda 的参数名（如 `warning`/`failure`/`target`/`parsedCard`/`historyItem`/`item`）。初版误用内容 lambda 参数名，导致 8 处 `Unresolved reference '...'`。已全部改为 `it` / 显式 `row ->`。
- **C.1 `ImagePreview` smart-cast**：`bitmap` 是 `var` 且被 `LaunchedEffect` 捕获，无法 smart-cast；改为先 `val currentBitmap = bitmap` 再判空，局部 `val` 才能 smart-cast 为非空。

### 待办（需维护者确认计划后推进）
- C.2 runBlocking（Autofill 路径，中风险，需荣耀 Android 17 真机验证）
- C.3 Room `SELECT *` 投影优化（工作量最大，需逐个 DAO 方法推 CI 验证）
- C.4.2 / C.4.3 / C.4.4 文档化全局 scope（设计选择）
- C.6 Compose 编译器优化（依赖 C.3 的投影 POJO 做 `@Immutable` 标注）

---

## 一、调研背景

2026-08-01 对代码库进行了全面的性能反模式扫描，覆盖 641 个 Kotlin/Java 源文件。以下是发现的所有性能问题，按严重程度排序。

### 已有的性能优势（肯定）

以下方面项目已做得很好，不在优化范围内：

| 项目 | 状态 |
| --- | --- |
| R8/minify release | ✅ 已启用 |
| `shrinkResources` | ✅ 已启用 |
| `org.gradle.parallel/caching/configuration-cache` | ✅ 已启用 |
| ABI splits 收敛 arm64-v8a | ✅ 已启用 |
| `GlobalScope` | ✅ 零使用 |
| LazyColumn 不稳定 key | ✅ 零（仅缺失 key，无错误 key） |
| N+1 查询模式 | ✅ 零 |
| 静态持有 Activity 引用 | ✅ 零 |
| ViewModel 协程 cancel | ✅ 全部正确 cancel |

---

## 二、问题清单与修复方案

### C.1 主线程 IO 阻塞（严重）

> **风险**：低修复风险，高收益。
> **验证**：CI 编译 + 真机打开附件预览验证无卡顿。

#### 问题描述

`AttachmentPreviewDialog.kt` 中 2 处在 `@Composable` 函数体内通过 `remember { }` 直接执行 IO，**阻塞主线程**：

| 行号 | 操作 | 问题 |
| --- | --- | --- |
| 125-131 | `BitmapFactory.decodeStream(openInputStream(uri))` | 主线程解码图片 |
| 251-255 | `openInputStream(uri).readText()` | 主线程读取文件全文 |

#### 修复方案

改为 `LaunchedEffect(uri) { withContext(Dispatchers.IO) { ... } }` + `mutableStateOf` 持有结果。

**参考实现**：同项目 `PasswordCustomIconSupport.kt:610-619` 已有正确写法：
```kotlin
LaunchedEffect(iconKey) {
    withContext(Dispatchers.IO) {
        // 解码操作
    }
}
```

#### 涉及文件

- `attachments/ui/AttachmentPreviewDialog.kt`（2 处修改）

---

### C.2 runBlocking 反模式（中高）

> **风险**：中。Autofill 路径对延迟极敏感，修改需谨慎。
> **验证**：CI 编译 + 真机测试 Autofill 填充流程。

#### 问题描述

5 处 `runBlocking` 调用，其中 1 处**无超时保护**：

| 文件 | 行号 | 超时保护 | 严重程度 | 说明 |
| --- | --- | --- | --- | --- |
| `AutofillServiceChecker.kt` | 218 | ❌ 无 | **高** | Autofill 服务被系统调起，DataStore 阻塞可能直接 ANR |
| `BaseBastionActivity.kt` | 52 | ✅ 200ms | 中 | 每次 Activity 创建都阻塞，读取语言设置 |
| `AutofillPickerActivityV2.kt` | 403 | ✅ 200ms | 中 | 读取 autoLockMinutes |
| `AccountFillPolicy.kt` | 38 | ✅ 200ms | 中 | 读取 separateUsernameAccountEnabled |
| `FilledDataBuilderNg.kt` | 37 | ✅ 200ms | 中 | 读取 autoLockMinutes |

#### 修复方案

**C.2.1 `AutofillServiceChecker.kt:218`（最高优先级）**

当前：
```kotlin
runBlocking { AutofillPreferences(context).isAutofillEnabled.first() }
```

方案：改为同步缓存读取。在 `BastionAutofillServiceNg.onCreate` 中预加载到内存变量，`AutofillServiceChecker` 直接读内存。

**C.2.2 `BaseBastionActivity.kt:52`**

当前：`runBlocking { withTimeout(200) { ... 语言设置 } }`

方案：改为 `attachBaseContext` 中使用同步 SharedPreferences 读取（语言设置不需要 DataStore 的类型安全），或缓存到 Application 级别。

**C.2.3 其余 3 处（Autofill 路径）**

`AutofillPickerActivityV2.kt:403`、`AccountFillPolicy.kt:38`、`FilledDataBuilderNg.kt:37` 都是 Autofill 路径读取 DataStore 配置。

方案：统一改为在 Autofill Service 启动时预加载到内存 DataClass，后续同步读取。

#### 涉及文件

- `autofill_ng/core/AutofillServiceChecker.kt`
- `ui/base/BaseBastionActivity.kt`
- `autofill_ng/AutofillPickerActivityV2.kt`
- `autofill_ng/AccountFillPolicy.kt`
- `autofill_ng/builder/FilledDataBuilderNg.kt`
- 可能新增：`autofill_ng/AutofillConfigCache.kt`（预加载缓存）

---

### C.3 Room SELECT * 过度查询（中）

> **风险**：中。涉及 DAO 查询重构，需确保不破坏现有 Flow。
> **验证**：CI 编译 + 真机大数据量（100+ 条目）首屏加载对比。

#### 问题描述

`SecureItemDao.kt`（22 处）和 `PasswordEntryDao.kt`（10+ 处）大量使用 `SELECT *`。列表查询返回完整实体（含 `itemData`、`password`、`notes` 等大字段），但 UI 列表只需 `id`、`title`、`icon` 等少量字段。

**影响**：大数据量下首屏加载明显变慢（反序列化全部列的 Cursor → Entity 对象）。

#### 修复方案

1. **创建投影 POJO**：
   ```kotlin
   data class PasswordEntryListItem(
       val id: Long,
       val title: String,
       val username: String,
       val website: String,
       val faviconUrl: String?,
       val isFavorite: Boolean,
       val categoryId: Long?,
       val keepassDatabaseId: Long?,
       val bitwardenVaultId: Long?,
       val updatedAt: Date
   )

   data class SecureItemListItem(
       val id: Long,
       val itemType: ItemType,
       val title: String,
       val isFavorite: Boolean,
       val categoryId: Long?,
       val keepassDatabaseId: Long?,
       val bitwardenVaultId: Long?,
       val updatedAt: Date
   )
   ```

2. **列表查询改投影**：
   ```kotlin
   // 之前
   @Query("SELECT * FROM password_entries WHERE deletedAt IS NULL ORDER BY title COLLATE NOCASE")
   fun getAllPasswords(): Flow<List<PasswordEntry>>

   // 之后
   @Query("SELECT id, title, username, website, faviconUrl, isFavorite, categoryId, keepassDatabaseId, bitwardenVaultId, updatedAt FROM password_entries WHERE deletedAt IS NULL ORDER BY title COLLATE NOCASE")
   fun getAllPasswordListItems(): Flow<List<PasswordEntryListItem>>
   ```

3. **详情页按 id 查完整实体**：已有 `getPasswordById(id)` 等方法，无需改动。

4. **ViewModel 适配**：列表 Flow 改为返回 ListItem POJO，点击进入详情时再查完整实体。

#### 涉及文件

- `data/PasswordEntryDao.kt` — `getAllPasswords`、`searchPasswords`、`getActiveLocalPasswords` 等
- `data/SecureItemDao.kt` — `getAllItems`、`getItemsByType`、`searchItems`、`searchItemsByType` 等
- 新增：`data/PasswordEntryListItem.kt`、`data/SecureItemListItem.kt`
- `viewmodel/PasswordViewModel.kt` — 适配新 POJO
- `viewmodel/TotpViewModel.kt` — 适配新 POJO
- `viewmodel/BankCardViewModel.kt`、`DocumentViewModel.kt` 等 — 适配新 POJO

#### 注意事项

- **不要修改 `@Entity` 定义**：投影 POJO 是独立的 data class，不影响 Room 表结构
- **不涉及数据库迁移**：纯查询层变更，不碰 schema
- **逐个 DAO 方法改**：每改一个推 CI 验证，确保 Flow 编译通过

---

### C.4 协程 Scope 生命周期（中）

> **风险**：中。涉及全局 scope 修改，需确保不破坏现有行为。
> **验证**：CI 编译 + 真机长时间使用后检查内存。

#### 问题描述

4 处长生命周期协程 Scope 未显式取消：

| 文件 | 行号 | Scope | 风险评估 |
| --- | --- | --- | --- |
| `OperationLogger.kt` | 38 | `CoroutineScope(Dispatchers.IO)` | **中** — 无 Job，无法 cancel，日志写入协程泄漏 |
| `ClipboardUtils.kt` | 39 | `SupervisorJob() + Main.immediate` | **低** — companion 全局 scope，延迟清空剪贴板（30s 后），scope 随进程结束 |
| `SettingsManager.kt` | 275 | `SupervisorJob() + Default` | **低** — companion 全局 scope，Eagerly stateIn 是设计选择（全局配置需常驻） |
| `TotpTicker.kt` | 19 | `SupervisorJob() + Default` | **低** — WhileSubscribed 可控，相对安全 |

#### 修复方案

**C.4.1 `OperationLogger.kt:38`（建议修复）**

当前：
```kotlin
private val scope = CoroutineScope(Dispatchers.IO)
```

改为：
```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

或更好的方案：注入 scope，在 Application 的 `onTerminate` 中 cancel。

**C.4.2 `ClipboardUtils.kt:39`（文档化）**

评估是否可用 `ProcessLifecycleOwner` scope 替代 companion scope。如果改动复杂度高，保留现状但添加注释说明设计意图。

**C.4.3 `SettingsManager.kt:275`（保留）**

Eagerly stateIn 是设计选择——全局配置需要常驻内存。保留现状，添加注释。

**C.4.4 `TotpTicker.kt:19`（保留）**

WhileSubscribed 已能控制订阅生命周期，相对安全。保留现状。

#### 涉及文件

- `utils/OperationLogger.kt`（修复）
- `utils/ClipboardUtils.kt`（文档化）
- `utils/SettingsManager.kt`（文档化）
- `ui/totp/TotpTicker.kt`（文档化）

---

### C.5 Compose 列表缺失 key（低）

> **风险**：低。纯 UI 优化。
> **验证**：CI 编译 + 真机列表滑动测试。

#### 问题描述

8+ 处 `LazyColumn` 的 `items(...)` 未传 `key` 参数，列表动画可能错乱 + 多余重组：

| 文件 | 行号 | 列表内容 | 建议 key |
| --- | --- | --- | --- |
| `UnifiedMoveToCategoryBottomSheet.kt` | 506 | 移动目标列表 | `key = { it.stableKey }` |
| `DedupEngineScreen.kt` | 622 | 去重警告列表 | `key = { it.id }` |
| `AddEditPasswordScreen.kt` | 3060 | 银行卡解析结果 | `key = { it.cardNumber }` |
| `CustomIconActionDialog.kt` | 147 | 图标行 | `key = { it.packageName }` |
| `AppSelector.kt` | 338 | 已选应用列表 | `key = { it.packageName }` |
| `SecurityAnalysisScreen.kt` | 855 | 安全分析项 | `key = { it.id }` |
| `SecurityAnalysisScreen.kt` | 887 | 安全分析项 | `key = { it.id }` |
| `SecurityAnalysisScreen.kt` | 919 | 安全分析项 | `key = { it.id }` |
| `GeneratorScreen.kt` | 2244 | 密码生成历史 | `key = { it.timestamp }` |

#### 修复方案

逐个补 `key` 参数。注意确认每个数据类是否有合适的唯一标识字段。

#### 涉及文件

- 上述 6 个文件，9 处修改

---

### C.6 Compose 编译器优化缺失（低）

> **风险**：低。构建配置调整 + 注解标注。
> **验证**：CI 编译 + 真机对比重组次数（用 Compose 诊断工具）。

#### 问题描述

项目使用 Kotlin 2.0.21 + Compose Compiler Gradle Plugin，但未配置 `composeCompiler { }` 块：

- 未开启稳定性报告（`reportsDestination`）
- 未配置 `stabilityConfig`
- 未显式标记 `@Immutable` / `@Stable`

**影响**：Compose 编译器默认将所有非原始类型参数视为不稳定，导致不必要的重组。

#### 修复方案

1. **开启稳定性报告**：

   在 `app/build.gradle` 添加：
   ```groovy
   composeCompiler {
       reportsDestination = layout.buildDirectory.dir("compose_reports")
   }
   ```

2. **构建一次 CI**，查看 `compose_reports/` 下的稳定性报告

3. **对纯数据类标注 `@Immutable`**：
   ```kotlin
   @Immutable
   data class PasswordEntryListItem(...)  // 投影 POJO（C.3 创建的）
   ```

4. **对可变但可观察的类标注 `@Stable`**（如有必要）

#### 涉及文件

- `app/build.gradle`（添加 composeCompiler 块）
- 各 data class / UI state class（添加 `@Immutable` 注解）

---

## 三、优先级与执行顺序

| 优先级 | 任务 | 预估工作量 | 风险 | 用户体验影响 |
| --- | --- | --- | --- | --- |
| **P0** | C.1 主线程 IO 阻塞修复 | 1 小时 | 低 | 附件预览不再卡顿 |
| **P0** | C.2.1 AutofillServiceChecker runBlocking | 1-2 小时 | 中 | 消除 Autofill ANR 风险 |
| **P1** | C.2.2 BaseBastionActivity runBlocking | 1 小时 | 低 | Activity 启动更流畅 |
| **P1** | C.2.3 其余 3 处 Autofill runBlocking | 2-3 小时 | 中 | Autofill 路径更稳定 |
| **P1** | C.3 Room SELECT * 投影优化 | 4-8 小时 | 中 | 大数据量首屏加速 |
| **P2** | C.4 协程 Scope 生命周期 | 2-3 小时 | 中 | 减少协程泄漏 |
| **P2** | C.5 Compose 列表补 key | 1 小时 | 低 | 列表动画更流畅 |
| **P2** | C.6 Compose 编译器优化 | 2-3 小时 | 低 | 减少不必要重组 |

### 建议接力顺序

```
Agent 1: C.1（主线程 IO）+ C.5（Compose key）  ← 最简单、立竿见影
    ↓
Agent 2: C.2.1（AutofillServiceChecker）+ C.2.2（BaseBastionActivity）  ← 消除 ANR 风险
    ↓
Agent 3: C.2.3（其余 Autofill runBlocking）+ C.4（协程 Scope）  ← Autofill 路径统一
    ↓
Agent 4-5: C.3（Room SELECT * 投影）  ← 工作量最大，可分批推进
    ↓
Agent 6: C.6（Compose 编译器优化）  ← 需要基于 C.3 的 POJO 做标注
```

---

## 四、CI 验证策略

### 4.1 编译闸门

`Build Debug APK (build gate)` 必须通过。

### 4.2 测试基线

- 当前基线：**19**
- 性能优化不应引入新的测试失败
- 如果 C.3 Room 投影改动了 DAO 方法签名，可能需要适配测试中的 mock

### 4.3 真机验证重点

| 任务 | 真机验证场景 |
| --- | --- |
| C.1 | 打开含附件的条目 → 点击附件预览 → 确认无卡顿 |
| C.2 | 系统设置中触发 Autofill → 确认填充正常、无 ANR |
| C.3 | 添加 100+ 条目 → 首屏列表加载速度对比 |
| C.4 | 长时间使用后 → 检查内存占用 |
| C.5 | 列表滑动 + 增删条目 → 确认动画无错乱 |
| C.6 | 查看重组次数（Layout Inspector / Compose Metrics） |

---

## 五、接力开发指南

### 5.1 环境

- 仓库：`https://github.com/Chaniug/bastion`
- 分支：`dev`（开发分支，验证后合并 `main`）
- 本地无 Android SDK，依赖 CI
- GHP token 推送：`git push https://<token>@github.com/Chaniug/bastion.git dev`

### 5.2 关键注意事项

1. **C.2 Autofill 路径修改需特别谨慎**：Autofill Service 被系统调起，生命周期不可控，任何阻塞都可能导致系统 watchdog ANR
2. **C.3 Room 投影逐个方法改**：每改一个 DAO 方法推一次 CI，确保 Flow 编译通过
3. **C.3 不改 Entity 定义**：投影 POJO 是独立的 data class，不影响 Room 表结构和迁移
4. **C.6 先开报告再标注**：先开启 `composeCompiler { reportsDestination }`，构建一次看报告，再针对性标注 `@Immutable`
5. **每个子任务独立提交**：不要把 C.1 和 C.3 混在一个 commit 里
6. **git push 偶发失败**：GnuTLS recv error，重试即可（最多 5 次）

### 5.3 代码规范

- 遵循现有 Kotlin 编码风格（4 空格缩进、trailing comma）
- 投影 POJO 放在 `data/` 包下，命名 `XxxListItem`
- 公共 API 添加 KDoc 注释
- 不引入新的第三方依赖

---

## 六、附录：性能扫描完整结果

### 扫描范围

- 641 个 Kotlin/Java 源文件
- 扫描日期：2026-08-01

### 扫描项汇总

| 类别 | 数量 | 严重程度 | 对应任务 |
| --- | --- | --- | --- |
| `runBlocking` 实际调用 | 5 处（1 处无超时） | 中-高 | C.2 |
| Composable 内直接 IO | 2 处 | 高 | C.1 |
| 长生命周期未取消 Scope | 4 处（2 处可控） | 中 | C.4 |
| `GlobalScope` | 0 | — | — |
| 静态持有 Activity 引用 | 0 | — | — |
| Room `SELECT *` 列表查询 | 40+ 处 | 中 | C.3 |
| N+1 查询模式 | 0 | — | — |
| LazyColumn 不稳定 key | 0 | — | — |
| LazyColumn 缺失 key | 8+ 处 | 低 | C.5 |
| 列表项内重计算 | 0 | — | — |
| R8/minify release | 已启用 | OK | — |
| Compose 编译器优化 | 缺失 | 低-中 | C.6 |
