# Bastion 架构升级 Phase B：代码治理与模块化

> **文档目的**：Phase A（MDBX 移除）完成后的后续优化路线，供多 agent 接力开发。
>
> **创建时间**：2026-08-01
> **状态**：设计阶段，待维护者确认优先级
> **前置条件**：Phase A ✅ 已完成并合入 main（`69c9f8b5`）
> **仓库**：https://github.com/Chaniug/bastion（dev 分支开发，验证后合并 main）

---

## 一、背景

Phase A 完成了 MDBX 存储后端的整删，代码库从 4 后端精简为 KDBX + Bitwarden + BastionLocal 三后端。但移除过程中暴露了以下深层问题：

1. **巨型文件**：19 个文件超过 2000 行，最大 `KeePassKdbxService.kt` 达 6331 行
2. **PasswordViewModel 膨胀**：4162 行、~140 个函数、7 个职责簇混合
3. **测试债务**：CI 基线容忍 19 个失败测试，9 个文件 23 处脆弱的源码文本断言
4. **遗留命名**：`Legacy` 前缀实际指 KeePass 回收站桥接（非 MDBX），`SOURCE_MONICA` 旧品牌名残留
5. **密封类同形冗余**：4 套 Ownership/Source sealed class 结构几乎一致
6. **DedupMergeTarget 扩展缺口**：去重合并仅支持 BastionLocal 目标

---

## 二、Phase B 任务分解

### B.1 遗留命名收敛（低成本、高可读性收益）

> **风险**：低。纯重命名，无逻辑变更。
> **验证**：CI 编译 + 测试基线。

#### B.1.1 TrashViewModel 中 `Legacy` → `KeePass` 命名修正

`TrashViewModel.kt` 中 6 处 `Legacy` 命名实际指 KeePass 回收站操作，严重误导：

| 当前命名 | 实际含义 | 建议命名 |
| --- | --- | --- |
| `restoreLegacyPasswordEntriesFromRecycleBin` | KeePass 回收站恢复密码 | `restoreKeePassPasswordEntriesFromRecycleBin` |
| `restoreLegacySecureItemsFromRecycleBin` | KeePass 回收站恢复安全项 | `restoreKeePassSecureItemsFromRecycleBin` |
| `deleteLegacyPasswordEntries` | KeePass 永久删除密码 | `deleteKeePassPasswordEntries` |
| `deleteLegacySecureItems` | KeePass 永久删除安全项 | `deleteKeePassSecureItems` |

**涉及文件**：
- `viewmodel/TrashViewModel.kt`（6 处调用）
- 定义所在的 `KeePassCompatibilityBridge` 接口/实现（方法声明）

#### B.1.2 `SOURCE_MONICA` 常量改名

`DedupMergeService.kt:950`：
```kotlin
// 当前
private const val SOURCE_MONICA = "bastion"
// 改为
private const val SOURCE_BASTION = "bastion"
```

**注意**：值 `"bastion"` 不变（数据库中已持久化），仅改常量名。

#### B.1.3 Monica Pass 加密兼容层文档化

`SecurityManager.kt` 中 `LEGACY_KEY_ALIAS_DATA = "monica_data_key_v2"` 等 Keystore 别名是品牌重塑前遗留。**暂不删除**（存量用户数据依赖），但应：
1. 添加注释说明历史背景
2. 抽取 `LegacyCiphertextCompat` 类型收口所有 legacy 加密路径
3. 评估未来是否可通过渐进式迁移淘汰旧别名

**涉及文件**：
- `security/SecurityManager.kt`（18 处 legacy 引用）
- `security/SensitiveFieldMigrationManager.kt`（8 处 legacy 引用）

---

### B.2 测试债务治理（中成本、高 CI 质量）

> **风险**：中。修改测试可能引入新回归。
> **验证**：CI 测试基线逐步下降。

#### B.2.1 摸清 19 个失败测试

**状态**：✅ 已完成（2026-08-01，CI run #30704111010）

当前 `BASELINE_FAILURES=19`，基线是"黑盒"。已从 CI 日志提取完整名单（共 525 测试，19 失败）：

| # | 测试类 | 方法 | 失败原因分类 | 修复状态 |
| --- | --- | --- | --- | --- |
| 1 | `ActiveFillHardeningRegressionGuardTest` | notificationFillIsOptInThrottledAndBoundToTheDetectedApp | 待分析 | ⬜ |
| 2 | `AutofillAuthResultLaunchModeRegressionGuardTest` | authResultActivitiesMustNotReuseExistingInstances | 待分析 | ⬜ |
| 3 | `AutofillDetectionIntegrationGuardTest` | parserAndAuthenticationCallbackShareTheConflictPolicy | 待分析 | ⬜ |
| 4 | `AutofillDropdownClickRegressionGuardTest` | dropdownCipherSuggestionsUseDirectValuesAndKeepManualFallback | 待分析 | ⬜ |
| 5 | `AutofillInlineClickRegressionGuardTest` | directInlineSuggestionsUseRealAuthenticationCallbackInsteadOfNoopIntent | 待分析 | ⬜ |
| 6 | `BiometricUnlockRegressionGuardTest` | mdkWrapperRebuildHandlesInvalidatedAndUnrecoverableKeystoreKeys | 待分析 | ⬜ |
| 7 | `BiometricUnlockRegressionGuardTest` | pageSwitchHotPathsDoNotRunAuthOrBitwardenSyncWorkOnMainThread | 待分析 | ⬜ |
| 8 | `CardBrandIconTest` | cardBrandIconFrameFollowsAppThemeInsteadOfSystemTheme | 待分析 | ⬜ |
| 9 | `ExpressiveTopBarKeyboardRegressionGuardTest` | searchFieldRestoresKeyboardWhenPressedAfterSystemDismissal | 待分析 | ⬜ |
| 10 | `KeePassOperationAvailabilityTest` | remoteDatabaseBlocksUnsafeSyncStates | 待分析 | ⬜ |
| 11 | `MultiPasswordSaveRegressionGuardTest` | normalPasswordPageRunsBatchDeleteThroughQuickStatusBar | 待分析 | ⬜ |
| 12 | `MultiPasswordSaveRegressionGuardTest` | normalPasswordPageShowsBatchTransferInQuickStatusBar | 待分析 | ⬜ |
| 13 | `MultiPasswordSaveRegressionGuardTest` | saveFailuresAreReportedWithNonSecretDiagnostics | 待分析 | ⬜ |
| 14 | `PasswordSuggestionUiRegressionGuardTest` | primaryActionUsesShortLabelInEnglishAndChinese | 待分析 | ⬜ |
| 15 | `PasskeyRemarkAndNavigationGuardTest` | authenticatorAndPasskeyShareOneDockDestinationWithBidirectionalControls | 待分析 | ⬜ |
| 16 | `PlusLocalActivationGuardTest` | activationCompletesLocallyWithoutBlockingProgressUi | 待分析 | ⬜ |
| 17 | `SplashThemeResourceTest` | startupUsesOnlyTheAndroidSystemSplashLayer | 待分析 | ⬜ |
| 18 | `SplashThemeResourceTest` | systemSplashFallbackUsesBastionM3LightAndDarkColors | 待分析 | ⬜ |
| 19 | `TimelineSnapshotIntegrationGuardTest` | roomDatabaseRegistersVersion73SnapshotMigration | **守卫断言过时**（Phase A 版本 73→74） | ✅ 已修复 |

**已修复**：第 19 项（`adc16a70`），将断言从 `version = 73` / `Migration(72, 73)` / `MIGRATION_72_73` 更新为 `version = 74` / `Migration(73, 74)` / `MIGRATION_73_74`。

**统计**：525 总测试，19 失败。移除第 19 项后剩余 18 个待分析。

#### B.2.2 5 个 `.kt.disabled` 文件处置

**状态**：✅ 已完成（2026-08-01）

背景：这 5 个文件都位于已废弃的 `com.bastion.app.autofill.*` 包下。该包在 autofill 重构中整体迁移为
`com.bastion.app.autofill_ng.*`，测试文件当时被改名挂起而非同步迁移，此后一直是死文件
（`Rebrand: Monica Pass -> Bastion` 提交 `4899931c` 后再无改动）。

| 文件 | 被测类现状 | 处置 |
| --- | --- | --- |
| `autofill/DirectEntryModeResolverTest.kt.disabled` | `DirectEntryModeResolver` 主代码已不存在 | 🗑 删除 |
| `autofill/core/MetricsCollectorTest.kt.disabled` | `MetricsCollector` 主代码已不存在 | 🗑 删除 |
| `autofill/strategy/MatchingStrategyTest.kt.disabled` | 整个 `strategy` 包已不存在（由 `DomainMatchStrategy.kt` 取代） | 🗑 删除 |
| `autofill/v2/BitwardenLikeAutofillMatcherTest.kt.disabled` | 已被 `autofill_ng/BitwardenLikeAutofillMatcherNgTest.kt`（205 行 / 9 用例）完整取代 | 🗑 删除 |
| `autofill/core/AutofillLoggerTest.kt.disabled` | `AutofillLogger` 仍在役（`autofill_ng/core/AutofillLogger.kt`），API 完全兼容 | ♻️ 迁移复活 |

**复活细节**：`AutofillLoggerTest` 迁移到 `autofill_ng/core/AutofillLoggerTest.kt`，package 改为
`com.bastion.app.autofill_ng.core`，测试体零改动。13 个用例覆盖日志分级、元数据、
4 类脱敏规则（密码/邮箱/手机号/身份证）、500 条环形缓冲上限、导出、统计、清除、异常附带。

可行性依据：
- `AutofillLogger` 的 Android 依赖只有 `android.util.Log`，且模块已开启 `unitTests.returnDefaultValues = true`
- `BoundedLogExecutorFactory` 是纯 JVM `ThreadPoolExecutor`，无 Android 依赖
- 未调用 `initialize(context)` 时 `persistentLogFile == null`，文件 IO 路径直接短路
- 全仓无其它测试引用 `AutofillLogger`，单例静态状态无跨测试污染风险

**净效果**：测试文件数 -4，有效用例数 +13，`src/test` 下 `.disabled` 文件归零。

#### B.2.3 守卫测试脆弱性治理

9 个文件、23 处 `projectFile(...).readText()` 源码文本断言，重命名即破：

| 优先级 | 文件 | readText 次数 | 治理方式 |
| --- | --- | --- | --- |
| P0 | `CardWalletSyncScopeTest.kt` | 8 | 改为行为测试（验证实际运行时行为而非源码文本） |
| P0 | `BiometricUnlockRegressionGuardTest.kt` | 2（含超长链式断言） | 拆分为多个精确断言，减少源码依赖 |
| P1 | `SplashThemeResourceTest.kt` | 6 | 资源验证改为运行时资源查询 |
| P2 | 其余 6 个文件 | 各 1 | 逐个评估是否可改为行为测试 |

**治理原则**：
- 守卫测试的核心价值是"防止架构回退"，应通过**运行时行为验证**（如实际调用函数检查返回值）而非**源码文本匹配**实现
- 对于"必须验证源码结构"的场景（如确保某文件不被引入），保留但缩小断言范围到最小必要字串

---

### B.3 PasswordViewModel 拆分（高成本、高架构收益）

> **风险**：中高。涉及核心业务逻辑，拆分不当可能引入回归。
> **验证**：CI 编译 + 测试基线 + 真机测试。

#### 当前状态

- **行数**：4162 行
- **函数数**：~140 个
- **职责簇**：7 个（见下表）
- **内部 new 的协作者**：9 个（未通过 DI 注入）

#### 拆分方案

| 职责簇 | 行范围 | 函数数 | 拆分目标 | 依赖 |
| --- | --- | --- | --- | --- |
| Bitwarden 离线缓存 | 240-1090 | ~25 | `BitwardenOfflineSecretCacheFacade` | bitwardenRepository, securityManager |
| KeePass 同步/对齐/TOTP 投影 | 1092-1727 | ~30 | `KeePassSyncCoordinator` | keepassBridge, repository |
| 类别过滤序列化/恢复 | 1728-1960 | ~12 | `CategoryFilterState` | settingsManager |
| 跨存储迁移 | 1961-2210 | ~14 | `PasswordMoveExecutor` | repository, keepassBridge, bitwardenRepository |
| Trash/批量删除 | 2599-2894 | ~14 | `PasswordDeleteOrchestrator` | repository, keepassBridge |
| 归档/取消归档 | 2895-3213 | ~22 | `PasswordArchiveOrchestrator` | repository, keepassBridge |
| 历史记录/自定义字段/主密码 | 3214-4162 | ~25 | `PasswordHistoryRecorder` + `MasterPasswordOps` | securityManager, passwordHistoryManager |

#### 执行步骤

1. **先抽取无状态工具类**：`CategoryFilterState`（纯数据序列化，无副作用）
2. **再抽取有状态协调器**：`KeePassSyncCoordinator`、`PasswordMoveExecutor`
3. **最后抽取敏感操作**：`MasterPasswordOps`（含 2 个 TODO，需补全）
4. **改为构造注入**：将 9 个内部 new 的协作者改为构造参数注入
5. **每拆一个推 CI 验证**，确保编译通过 + 测试基线不上升

#### 注意事项

- `CategoryFilter` sealed class（第 82 行，13 个分支）应随 `CategoryFilterState` 一起移出
- `BitwardenRecoveryResult`、`BitwardenSyncRawHistoryItem`、`KeePassCustomFieldFingerprint` 等私有类型应移入各自协调器
- 第 3572 行 `changePassword` 和第 3608 行 `saveSecurityQuestions` 含 TODO，拆分时应一并补全

---

### B.4 密封类同形收敛（中成本、中收益）

> **风险**：中。涉及类型系统变更，影响面广。
> **验证**：CI 编译 + 测试基线。

#### 当前状态

4 套 sealed class 分支结构几乎一致：

| sealed 类 | 文件 | BastionLocal | KeePass | Bitwarden | Conflict |
| --- | --- | --- | --- | --- | --- |
| `PasswordOwnership` | `PasswordOwnership.kt` | object | (databaseId, entryUuid) | (vaultId, cipherId) | (kp, bw) |
| `SecureItemOwnership` | `SecureItemOwnership.kt` | object | (databaseId, entryUuid) | (vaultId?, cipherId?) | (kp, bw) |
| `PasskeyOwnership` | `PasskeyOwnership.kt` | object | (databaseId) | (vaultId?, cipherId?) | (kp, bw) |
| `PasswordSource` | `PasswordSource.kt` | object | (databaseId?, entryUuid?) | (vaultId?, cipherId?) | (kp, bw) |

#### 可选方案

**方案 A：引入泛型基接口**（推荐）
```kotlin
sealed interface ItemOwnership<out K : KeePassBinding, out B : BitwardenBinding> {
    object BastionLocal : ItemOwnership<Nothing, Nothing>
    data class KeePass<K, B>(val binding: K) : ItemOwnership<K, B>
    data class Bitwarden<K, B>(val binding: B) : ItemOwnership<K, B>
    data class Conflict<K, B>(val keepass: K?, val bitwarden: B?) : ItemOwnership<K, B>
}
```

**方案 B：保持独立但统一扩展函数**
- 不改变 sealed class 定义
- 抽取公共 `resolveOwnership()` 模板到扩展函数
- 统一 `hasBitwardenBinding()`、`isLocalOnly()` 等工具函数

> **建议**：先用方案 B（低风险），等 PasswordViewModel 拆分完成后再评估方案 A。

#### 障碍

各 sealed class 的字段可空性不一致（如 SecureItemOwnership 的 Bitwarden vaultId 可空 vs PasswordOwnership 非空），统一前需先确认可空性语义差异是否有业务原因。

---

### B.5 大型 Screen 文件拆分（低优先级、高工作量）

> **风险**：低。纯 UI 拆分，不涉及业务逻辑。
> **验证**：CI 编译 + 真机 UI 测试。

#### Top 5 待拆分 Screen 文件

| 文件 | 行数 | 拆分方向 |
| --- | --- | --- |
| `AddEditPasswordScreen.kt` | 4,881 | 按表单区块拆分：基本信息 / 自定义字段 / 存储目标 / TOTP 绑定 / 高级选项 |
| `PasswordDetailScreen.kt` | 3,490 | 按详情区块拆分：概览 / 字段列表 / 历史记录 / 操作菜单 |
| `TimelineScreen.kt` | 3,307 | 按时间线视图拆分：列表 / 详情 / 对比 / 恢复 |
| `SimpleMainScreen.kt` | 3,155 | 按标签页拆分：密码 / 安全项 / 钥匙 / 设置 |
| `MainActivity.kt` | 3,678 | 按 5 个 TODO 补全 + 导航逻辑抽离 |

#### MainActivity.kt 的 5 个 TODO

| 行号 | TODO 内容 | 处置 |
| --- | --- | --- |
| 2534 | 修改密码逻辑 | 补全实现（与 B.3 的 `MasterPasswordOps` 联动） |
| 3603 | 重试 | 补全 |
| 3606 | 删除 | 补全 |
| 3610 | 全部重试 | 补全 |
| 3613 | 清除已完成 | 补全 |

---

### B.6 DedupMergeTarget 扩展（功能增强、低优先级）

> **风险**：中。新增功能，需设计 + 测试。
> **验证**：新增单元测试 + CI。

#### 当前状态

`DedupMergeService.kt` 的 `DedupMergeTarget` 仅 `BastionLocal` 一个分支，无法将重复条目合并到 KeePass 或 Bitwarden 目标。

#### 扩展方向

1. 新增 `DedupMergeTarget.KeePass(databaseId, groupPath)` 分支
2. 新增 `DedupMergeTarget.Bitwarden(vaultId, folderId)` 分支
3. 在 `buildTargetEntry()` 和 `buildTargetItem()` 的 `when` 中添加对应分支
4. 更新 UI 允许用户选择合并目标

> **注意**：此扩展与 `PasswordSource.kt` 中"无入口函数"现象呼应，是同一抽象缺失的两个表现。建议在 B.4 密封类收敛后一并处理。

---

## 三、优先级与建议执行顺序

| 优先级 | 任务 | 预估工作量 | 风险 | 建议执行者 | 状态 |
| --- | --- | --- | --- | --- | --- |
| **P0** | B.1 遗留命名收敛 | 1-2 小时 | 低 | 单 agent | ✅ 完成（`54c2111f`，CI #30704111010） |
| **P0** | B.2.1 摸清 19 个失败测试 | 1 小时 | 无 | 单 agent | ✅ 名单已摸清，修复 1/19（`adc16a70`） |
| **P0** | B.2.2 处置 5 个 .disabled 文件 | 2-3 小时 | 低 | 单 agent | ✅ 完成（删 4 复活 1） |
| **P1** | B.2.3 守卫测试治理 | 4-6 小时 | 中 | 单 agent | ⬜ 未开始 |
| **P1** | B.3 PasswordViewModel 拆分 | 8-16 小时 | 中高 | 多 agent 接力 | ⬜ 未开始 |
| **P2** | B.4 密封类同形收敛 | 4-8 小时 | 中 | 单 agent（B.3 完成后） | ⬜ 未开始 |
| **P2** | B.5 Screen 文件拆分 | 8-16 小时 | 低 | 多 agent 接力 | ⬜ 未开始 |
| **P3** | B.6 DedupMergeTarget 扩展 | 4-8 小时 | 中 | 单 agent（B.4 完成后） | ⬜ 未开始 |

> **性能优化任务**见 Phase C：[`docs/architecture-phaseC-performance.md`](architecture-phaseC-performance.md)

### 建议接力顺序

```
Agent 1: B.1（命名收敛）+ B.2.1（摸清失败测试）+ B.2.2（处置 disabled）
    ↓
Agent 2: B.2.3（守卫测试治理）+ B.2.1 续（逐个修复失败测试，下调基线）
    ↓
Agent 3-5: B.3（PasswordViewModel 拆分，每次拆 1-2 个职责簇）
    ↓
Agent 6: B.4（密封类收敛）
    ↓
Agent 7+: B.5（Screen 拆分）+ B.6（DedupMergeTarget 扩展）
```

> Phase B 和 Phase C 可并行推进，互不依赖。

---

## 四、CI 验证策略（沿用 Phase A）

### 4.1 编译闸门

`Build Debug APK (build gate)` 必须通过。

### 4.2 测试基线

- 当前基线：**19**
- B.2 完成后目标：逐步降至 **≤10**
- 修好测试后必须同步下调 `BASELINE_FAILURES`（`.github/workflows/main.yml:222`）

### 4.3 无本地编译环境

全部依赖 GitHub Actions 日志。推送后观察 CI，编译失败时根据错误信息修正。

---

## 五、接力开发指南

### 5.1 环境

- 仓库：`https://github.com/Chaniug/bastion`
- 分支：`dev`（开发分支，验证后合并 `main`）
- 本地无 Android SDK，依赖 CI
- GHP token 推送：`git push https://<token>@github.com/Chaniug/bastion.git dev`

### 5.2 关键注意事项

1. **每个子任务独立提交**：不要把 B.1 和 B.3 混在一个 commit 里
2. **每提交一次推 CI 验证**：编译 + 测试基线
3. **测试基线只降不升**：`BASELINE_FAILURES` 只能改小，不能改大
4. **PasswordViewModel 拆分时**：先抽取无状态部分，再抽有状态部分，每步推 CI
5. **重命名时全局搜索**：用 `grep -r "oldName"` 确认所有引用点都更新
6. **git push 偶发失败**：GnuTLS recv error，重试即可（最多 5 次）

### 5.3 代码规范

- 遵循现有 Kotlin 编码风格（4 空格缩进、trailing comma）
- 新文件放在合理的包路径下
- 公共 API 添加 KDoc 注释
- 不引入新的第三方依赖（除非维护者确认）

---

## 六、附录：Phase A 完成状态确认

| 检查项 | 结果 |
| --- | --- |
| MDBX import 残留 | ✅ 零 |
| MDBX sealed 分支残留 | ✅ 零 |
| MDBX 字符串资源残留 | ✅ 零 |
| DedupMergeService MDBX 逻辑 | ✅ 零 |
| TrashViewModel MDBX 逻辑 | ✅ 零 |
| SecurityManager MDBX 逻辑 | ✅ 零 |
| PasswordDatabase 历史迁移代码 | ✅ 保留（Room 迁移链不可断） |
| CI 编译 + 测试 + 发布 | ✅ success |
