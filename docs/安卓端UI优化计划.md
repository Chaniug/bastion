# Bastion 安卓端 UI 优化计划

> 目标：对安卓端 UI 做系统性的「正确性 / 维护性 / 一致性 / 体验」优化，而非视觉重设计。
> 分支策略：dev 开发 → Android CI debug 绿 → 合 main。不碰 `desktop/` 桌面端。
> 状态：📋 **待确认**。本文件用于记录方案与精确清单，供本 agent 或其他 agent 接力。

---

## 0. 结论先行

- **需要优化**，但没有「必须立刻修的严重 UI bug」。主要是**代码债 + 性能 + 一致性 + 体验**的优化空间，证据充分（见 §1）。
- 最痛的三个点：① lint 基础设施退化（Compose 检查被跳过）；② 13 个 2000+ 行 God File；③ ~~未 remember 的 state~~（已判定 0 真实 bug，见 §2.1）。

---

## 1. 体检数据（基准，2026-08-14）

| 指标 | 数值 | 说明 |
|---|---|---|
| `@Composable` 函数数 | 912（ui/ + autofill_ng/ + passkey/） | 体量很大 |
| `ui/` 代码量 | 261 个 .kt，12.7 万行 | — |
| God File（>2000 行） | **13 个**，最大 `AddEditPasswordScreen.kt` 4881 行 | 维护/协作/复用难 |
| 未与 `remember` 同行的 `mutableStateOf` | ~~81 处候选~~ → **判定后 0 真实 bug**（15 状态持有类 + 66 多行 remember/rememberSaveable） | 见 §2.1 |
| lint 基线 | `lint-baseline.xml` 2.6 万行 / 1.2MB | 债量可观 |
| lint 债细分 | UnusedResources **586** / TypographyEllipsis **91** / PluralsCandidate **89** / ObsoleteSdkInt **43** / TypographyDashes 25 / Overdraw 4 / RtlSymmetry 4 | 见 §2.3 |
| lint 基础设施 | **Compose lint 检查整体被跳过**（`ObsoleteLintCustomCheck`：Kotlin analysis API 不匹配）+ 10 项检查被全局 `disable` + lint 仅 PR 触发 | 见 §2.0 |

### 1.1 God File 清单（>2000 行）

| 文件 | 行数 |
|---|---|
| `ui/screens/AddEditPasswordScreen.kt` | 4881 |
| `autofill_ng/AutofillPickerActivityV2.kt` | 4339 |
| `ui/vaultv2/VaultV2Pane.kt` | 4241 |
| `ui/screens/PasswordDetailScreen.kt` | 3490 |
| `ui/screens/TimelineScreen.kt` | 3307 |
| `ui/SimpleMainScreen.kt` | 3159 |
| `ui/screens/PageAdjustmentCustomizationScreen.kt` | 2738 |
| `ui/screens/GeneratorScreen.kt` | 2587 |
| `ui/screens/PasskeyListScreen.kt` | 2469 |
| `ui/screens/LocalKeePassScreen.kt` | 2393 |
| `autofill_ng/EnhancedAutofillStructureParserV2.kt` | 2043 |
| `ui/password/PasswordListContent.kt` | 2021 |
| `ui/screens/WebDavBackupScreen.kt` | 2007 |

### 1.2 被全局 `disable` 的 Compose 检查（`app/build.gradle` lint 块）

`NullSafeMutableLiveData`、`FrequentlyChangingValue`、`RememberInComposition`、`FlowOperatorInvokedInComposition`、`UnrememberedMutableState`、`AutoboxingStateCreation`、`CoroutineCreationDuringComposition`、`StateFlowValueCalledInComposition`、`ProduceStateDoesNotAssignValue`、`MissingTranslation`。

> 其中 `UnrememberedMutableState` / `StateFlowValueCalledInComposition` / `FlowOperatorInvokedInComposition` 直接对应「状态重置 / 漏重组 / 组合期副作用」三类真实 bug。全局禁用意味着这些反模式即使出现也不会报警。

### 1.3 体验/架构层已知项

- `AndroidManifest.xml` `android:enableOnBackInvokedCallback="false"`：禁用了 Android 13+ 预测性返回手势。
- 8 个 `activity-alias` 启动别名 + `LauncherEntryRepairReceiver`：图标切换逻辑复杂，曾出启动入口 bug。
- `material3Expressive = 1.5.0-alpha16`（生产跑 alpha）。
- 自动填充入口 UI 多套变体（`AutofillPickerActivity` vs `AutofillPickerActivityV2`、多个 Save/Unlock 全屏 Activity），疑似冗余。

### 1.4 已做好的（不在本计划范围）

LazyColumn key 补齐（Phase C.5）、Compose 编译器稳定性报告已开（C.6 第 1 步）、功耗/内存「阶段一」、`resConfigs` 瘦身、`@Immutable` 标注待 C.3 POJO 落地后实施。

---

## 2. 优化项（按优先级）

### 2.0 P0 — 修复 lint 基础设施（前置，决定 P3 能否被有效守护）

- **问题**：`lint-baseline.xml` 第一条 `ObsoleteLintCustomCheck` 表明 Compose lint 注册表引用了无效 Kotlin analysis API，导致 `UnnecessaryComposedModifier`、`SuspiciousModifierThen`、`SuspiciousCompositionLocalModifierRead` 等 Compose 官方 UI 检查**全部被跳过**。
- **根因线索**：lint 基线由 AGP 8.7.3 生成，现工程已升 AGP 9.1.1 / Kotlin 2.3.21，Compose lint 库与 Kotlin analysis API 版本失配。
- **方案**：升级 Compose lint 相关依赖到与 Kotlin 2.3.x 匹配的版本；重新跑 `./gradlew :app:lintDebug` 确认 `ObsoleteLintCustomCheck` 消失、Compose 检查恢复。
- **验收**：`lintDebug` 不再报 `ObsoleteLintCustomCheck`，且能输出 Compose 检查结果。
- **风险**：低（只动构建依赖 + 配置，不动 UI 逻辑）；但需逐个确认升级后无新报错。

### 2.1 P1 — 未 `remember` 的 `mutableStateOf` 审计（正确性，优先）

> ✅ **判定完成（2026-08-14）**：81 处候选经脚本三分类 + 逐条读上下文确认，实际**全部合法**——15 处状态持有类（`CategoryManagementState`/`PasswordFieldActionMenu`/`VaultV2PaneState`）、66 处多行 `remember`/`rememberSaveable { }` 写法。**0 个真实 bug，本项可关闭，无需修复**。初版 grep 因不识别多行 `remember` 造成"81 处"误报。

**判定规则（三步，逐处执行）：**

1. 若在**非 `@Composable` 的类体**里（状态持有类）→ **合法，跳过**。已知合法类：`ui/category/CategoryManagementState.kt`、`ui/components/PasswordFieldActionMenu.kt`、`ui/vaultv2/VaultV2PaneState.kt`。
2. 若上一行是 `remember(key) {` 或 `val x = remember(...) {` 的多行写法 → **合法，跳过**。
3. 若直接在 `@Composable` 函数体内 `val/var x = mutableStateOf(...)` 且无 `remember` → **BUG**，改为 `val x = remember { mutableStateOf(...) }`（有 key 依赖则 `remember(key) {...}`）。

**候选清单（81 处，`文件:行号`）**——完整列出，接力时逐条打勾：

```
ui/CompactDraggableTabContent.kt:172
ui/SimpleMainScreen.kt:765
ui/SimpleMainScreen.kt:959,962,965,968
ui/category/CategoryManagementState.kt:38,40,43          ← 状态持有类，跳过
ui/components/ExpressiveTopBar.kt:87
ui/components/MultiStorageTargetPickerBottomSheet.kt:174
ui/components/OutlinedTextField.kt:61
ui/components/PasswordFieldActionMenu.kt:87,88,89         ← 状态持有类，跳过
ui/components/TotpCodeCard.kt:124,127,253
ui/password/PasswordBatchMoveSupport.kt:744
ui/password/PasswordListCategoryChipMenuModuleDragStateSupport.kt:39
ui/password/PasswordListContent.kt:728,1077,1140,1342
ui/password/PasswordListContentSupport.kt:775,781
ui/screens/AddEditPasswordScreen.kt:3829
ui/screens/AddEditTotpScreen.kt:154,159,168
ui/screens/BottomNavSettingsScreen.kt:37
ui/screens/CustomColorSettingsScreen.kt:124,127,130,133,136
ui/screens/DeveloperSettingsScreen.kt:112,115,118,121,124,505
ui/screens/LocalKeePassOneDriveBrowser.kt:627
ui/screens/LocalKeePassScreen.kt:949,1599
ui/screens/LocalKeePassWebDavBrowser.kt:668
ui/screens/MasterPasswordLockingSettingsScreen.kt:64
ui/screens/NoteDetailScreen.kt:77
ui/screens/PageAdjustmentCustomizationScreen.kt:240,558,676,679,682,685,688,691,1408,1924
ui/screens/PasskeySettingsScreen.kt:57,60,63
ui/screens/SendScreen.kt:919
ui/screens/SupportAuthorScreen.kt:61
ui/screens/SettingsScreen.kt:270
ui/totp/TotpListContent.kt:911
ui/vaultv2/VaultV2Pane.kt:800,1265,1605,1611,1617,2000,2195
ui/vaultv2/VaultV2PaneState.kt:56,60,63,66,69,75,78,80,82   ← 状态持有类，跳过
autofill_ng/AutofillPickerActivityV2.kt:1567
```

> 注：清单由 `grep -rEn 'mutableStateOf\(' ui/ autofill_ng/ | grep -v remember` 生成，属「候选」，需按上面三步过滤。**经逐条核实：0 个真实 bug**，本清单仅供存档，无需逐条修复。

**验收**：逐文件改后推 dev → Android CI debug 绿；真机（荣耀 Android 17）重点验证：设置页开关来回切不丢状态、TOTP 卡片显隐、列表筛选/折叠状态不闪回。

### 2.2 P2 — God File 拆分（维护性，风险中高）

- 从最痛的 `AddEditPasswordScreen.kt`（4881 行）和 `VaultV2Pane.kt`（4241 行）入手，把可复用的子组件（表单区块、条目行、对话框、工具栏）抽成独立 composable。
- **原则**：纯搬移，不改行为；每拆一块推一次 CI；不碰 `@Entity`/业务逻辑层。
- **风险**：中高（大量状态提升/参数传递），必须分批、真机冒烟。

### 2.3 P3 — lint 债清理（一致性/包体，低风险机械活）

| 项 | 数量 | 处理 | 收益 |
|---|---|---|---|
| `UnusedResources` | 586 | 删除未引用资源（strings/drawables/layouts） | 减包体、减混淆面 |
| `TypographyEllipsis` | 91 | `"..."` → `"…"`（U+2026） | 排版一致性 |
| `PluralsCandidate` | 89 | 补 `<plurals>` 资源 | 多语言语法正确 |
| `ObsoleteSdkInt` | 43 | 清理 `if (Build.VERSION.SDK_INT < 26)` 等恒假分支（minSdk 26） | 删死代码 |
| `TypographyDashes`/`RtlSymmetry`/`Overdraw`/`UseAppTint` | 少量 | 逐条按建议修 | 一致性/性能 |

> 源头以 `./gradlew :app:lintDebug` 实时报告为准（`lint-baseline.xml` 是历史基线，路径相对 `Bastion/app/`）。

### 2.4 P4 — 体验/架构（重点改动，需单独确认）

- **预测性返回**：评估 `enableOnBackInvokedCallback` 改 `true`，需排查依赖 `onBackPressedDispatcher` 的页面是否兼容，并真机验证系统返回手势动画。
- **launcher alias 精简**：梳理 8 个别名的用途（Modern/Classic/Visible/Bastion 图标切换），合并为最少必要集合，同步更新 `LauncherEntryRepairReceiver`。
- **`material3Expressive` alpha → 稳定**：评估升级到稳定版或回退到标准 material3。
- **自动填充 UI 变体收敛**：确认 `AutofillPickerActivity` / `AutofillPickerActivityV2` 等是否并存冗余，保留现行生效的一套。

---

## 3. 接力顺序建议

```
P0（lint 基础设施修复）→ P1（state 审计，正确性）→ P3（机械 lint 债）
     → P2（God File 拆分，工作量大）→ P4（体验/架构，需用户逐项确认）
```

- P0/P1/P3 风险低、可并行小步推。
- P2 按文件拆分，一个 God File 一个 commit。
- P4 每项都是「重点改动」，必须出细化方案、用户确认后再动。

---

## 4. 明确不做（范围外）

- 视觉重设计、换主题/配色。
- 新增功能、新增页面。
- Room `SELECT *` 投影优化（已在 `docs/architecture-phaseC-performance.md` C.3，不重复）。
- 与安卓核心逻辑（Bitwarden 同步 / KDBX / 安全）相关的改动。

---

## 5. 验证策略

1. 每批改动推 dev → `Android CI debug`（Build Debug APK 闸门 + 单测基线 `BASELINE_FAILURES=0`）。
2. 真机（荣耀 Android 17）：对应屏幕冒烟（状态保持、列表滑动、返回手势、输入）。
3. lint 报告对比：`./gradlew :app:lintDebug` 输出计数，确认目标 issue 下降。
4. 回滚：逐 commit `git revert`。

---

## 6. 执行进度

### 2026-08-14 批次一：纯文本 + 死代码清理（低风险，已推 dev）

| 任务 | 计划量 | 实际 | 状态 | 说明 |
|------|--------|------|------|------|
| P3-a TypographyEllipsis | 91（baseline 行号已过期） | 62 处 `"..."`→`"…"` | ✅ 完成 | 两份 strings.xml 各 31 处；baseline 行号过期，按真实 grep 定位更彻底 |
| P3-c ObsoleteSdkInt | 43 | 42 处死分支 | ✅ 完成 | minSdk=26，删除 `SDK_INT` 对 M(23)/N(24)/O(26) 的恒真/恒假分支；覆盖 28 个 kt 文件 |
| P3-b TypographyDashes | 25 | 0 | ⏭️ 跳过 | 真实问题是数字范围 `-`（`0-9`/`1-30`/`100-0000` 日本邮编）；含误报、纯装饰，价值低，待确认 |

**改动文件**：30 个（28 个 kt + 2 个 strings.xml），净删 ~151 行。
**验证**：推 dev 后由 `Android CI debug` 编译 Debug APK + 单测基线兜底；通过后合 main，preview Release 产出 APK。

### 待办（需用户确认或更高风险）
- P3-d PluralsCandidate（89 处）：需为数量字符串补 `<plurals>` 资源并改 `getString` 调用点，涉及资源结构改动 → 先出细化方案再动。
- P3-e 少量一致性项（RtlSymmetry/Overdraw/UseAppTint/DefaultLocale/StaticFieldLeak）：逐条按建议修，价值中等。
- P3-f UnusedResources（586）：需警惕 `getIdentifier` 反射引用，仅删高置信未用项；风险较高 → 先出细化方案再动。
- lint-baseline.xml 中已修复的 TypographyEllipsis(91)/ObsoleteSdkInt(43) 条目已失效，待 lint 跑过后重新生成 baseline。
