# PasswordListContent 拆分计划（让 ART 能 JIT 编译）

> 生成时间：2026-09-05
> 起点提交：`50c6192`（第一步已合入 dev / main）
> 数据来源：开发日志导出 `bastion_logs_20260905_014034.txt`（2150 行 / 43 秒）
> 相关文档：[lint 债务清理计划](./lint债务清理计划.md) · [compose-material3-api-migration-plan-2026-09](./compose-material3-api-migration-plan-2026-09.md)

---

## 零、一句话结论

`PasswordListContent` 一个函数 1595 行，编译后 **18895 条指令**，超过 ART 的方法编译上限，导致**永远无法被 JIT 编译、只能解释执行**，且每次尝试编译都会刷一条 `Method exceeds compiler instruction limit`（实测 428 次）。

要把这块拆到上限以下，需要分多轮，按"内聚度"逐块把状态与 UI 区块下沉到独立 Composable。**已完成第一步**（快捷筛选开关下沉，`50c6192`）。后续步骤见 [三、下一步候选](#三下一步候选按内聚度排序)。

---

## 一、背景与问题

### 1.1 现象

实测 logcat：

```
I com.bastion.app: Method exceeds compiler instruction limit: 18895 in void
  com.bastion.app.ui.PasswordListContentKt.PasswordListContent(...)
```

- 一份 2150 行的开发日志里，这一条**刷了 428 次**（占应用进程日志的 90%）。
- 同一时段还观察到主线程栈顶 `androidx.compose.runtime.GapComposer.updateValue`——说明确实有真实的重组耗时，不只是日志噪音。

### 1.2 根因

ART 对单方法有指令数上限（约 16K，因版本略有差异）。超过就**拒绝 JIT 编译**，方法只能走解释执行：
- 列表重组性能受损
- 每次尝试编译都打一条警告

### 1.3 目标

降到上限以下，让 JIT 能编译 `PasswordListContent`。**唯一权威判定方式**：装包后 logcat 不再出现 `Method exceeds compiler instruction limit`。

---

## 二、现状（截至 `50c6192`）

### 2.1 文件与函数范围

`app/src/main/java/com/bastion/app/ui/password/PasswordListContent.kt`：

| 函数 / 类 | 行号（参考，会变） | 说明 |
|------|------|------|
| `PasswordListContent` | 313 - ~1907 | 主函数，1595 行，**超标点** |
| `PasswordListQuickFilterToggles`（class） | ~1883 | 第一步新增的状态容器 |
| `rememberPasswordListQuickFilterToggles` | ~1930 | 第一步新增的工厂 Composable |
| `PasswordListMainPaneHost` | ~2001 | 已存在的子 Composable（**参数签名不要动**） |
| 嵌套 `RenderPasswordListTopSection` | ~1550 | 嵌套在 PasswordListContent 内，转发 ~60 个参数 |
| 嵌套 `RenderPasswordListMainPaneHost` | ~1647 | 嵌套在 PasswordListContent 内，转发 ~100 个参数 |

> ⚠️ 行号会随每步拆分变化。**定位时用函数名/字符串搜索，不要靠行号。**

### 2.2 第一步（`50c6192`）做了什么

下沉"快捷筛选"状态块（原 719-753）：

```kotlin
// 原来：15 个开关 + 3 个派生 + 3 个清空 effect，全内联
var quickFilterFavorite by rememberSaveable { mutableStateOf(false) }
// ... 14 个类似
val hasAnyWifiEntry = remember(passwordEntries) { passwordEntries.any { it.isWifiEntry() } }
LaunchedEffect(hasAnyWifiEntry) { if (!hasAnyWifiEntry) quickFilterWifi = false }
// ...

// 现在：1 行调用
val quickFilterToggles = rememberPasswordListQuickFilterToggles(passwordEntries)
```

15 个开关 + WIFI/SSH/条码三个"是否存在对应条目"的派生值 + 3 个清空 `LaunchedEffect` 全部进 `rememberPasswordListQuickFilterToggles` 与容器 `PasswordListQuickFilterToggles`。父函数处由 35 行声明收缩为 1 行调用。

### 2.3 必须遵守的约束（后续步骤同样适用）

1. **各开关仍用 `rememberSaveable`**——屏幕旋转、Activity 重建后的恢复语义不变。
2. **状态容器每次重组同步最新值**，读取方拿到的值与原先逐个读布尔完全等价。
3. **重置副作用、筛选函数入参、两个 UI 组件的 setter 回调均经容器转发**；**组件契约未动**（`PasswordListMainPaneHost` 的参数名保持原名）。
4. 行为严格等价，**不做功能改动**。

---

## 三、下一步候选（按内聚度排序）

### 3.1 第二步：`configuredQuickFilterItems` + 重置 effect

**范围**（行号以当前代码为准）：

```kotlin
val configuredQuickFilterItems = remember(
    appSettings.passwordPageAggregateEnabled,
    aggregateUiState.visibleContentTypes
) {
    appendAggregateContentQuickFilterItems(
        configuredItems = resolvedQuickFilterBaseItems(appSettings.passwordListQuickFilterItems),
        visibleTypes = aggregateUiState.visibleContentTypes,
        aggregateEnabled = appSettings.passwordPageAggregateEnabled,
        includePasskeyChip = false
    )
}

LaunchedEffect(configuredQuickFilterItems) {
    if (FAVORITE !in configuredQuickFilterItems) quickFilterToggles.favorite = false
    // ... 11 个类似
}
```

**依赖**：`appSettings`、`aggregateUiState`、`quickFilterToggles`（已下沉）

**做法**：
- 抽到 `@Composable private fun rememberPasswordListConfiguredQuickFilterItems(appSettings, aggregateUiState): List<PasswordListQuickFilterItem>`
- 重置 effect 一并搬进去（访问 `quickFilterToggles` 的 setter，把它作为参数传入）

**收益估计**：约 30 行 + 1 个 effect。

### 3.2 第三步：两个嵌套 Composable（单步最大收益）

**范围**：
- `RenderPasswordListTopSection`（~1550-1637，转发 ~60 个参数给 `PasswordListTopSection`）
- `RenderPasswordListMainPaneHost`（~1647-1748，转发 ~100 个参数给 `PasswordListMainPaneHost`）

**做法**：从嵌套（在 `PasswordListContent` 内）提升为顶层 `private @Composable fun`。

**为什么有效**：嵌套函数能访问父函数局部变量 → 提升后这些变量必须显式传参；**参数值计算**（如 `isAuthenticated && topActionsMenuExpanded`、setter lambda `{ x = it }`）随之移到新函数内 → 父函数指令数真正减少。

**注意**：
- 参数极多（~60 / ~100），但都是机械转发。**推荐用 IDE 的 Extract Function 重构**，比手写可靠。
- 提升后，父函数仍持有那些状态（被多处使用），只是 UI 调用 + 参数计算移走。

**收益估计**：~160 行参数计算移走，是单步最大收益。

### 3.3 第四步：其余状态与派生（⏸ 待实机数据决策 + 需用户确认后动手）

约 80 个 `collectAsState` + 大量 `remember` / `derivedStateOf` + 23 个 `LaunchedEffect`/`BackHandler`。按职责分组继续下沉：

- 滚动状态（`listState`、`listTopPadding`、滚动重置 effect）
- 选择状态（`isSelectionMode`、`selectedItemKeys`、`swipeSelectionAnchorKey` + 相关 effect）
- 对话框状态（`showMoveToCategoryDialog`、`showManualStackConfirmDialog`、`showBatchDeleteDialog` 等）
- 派生筛选结果（`preStackFilteredPasswordEntries`、`visiblePasswordEntries`、`visibleAggregateItems`）

每组下沉为一个独立 Composable + 状态容器。

**⏸ 决策门槛（2026-09-05 补充）**：步骤 0-3 完成后主函数余 1410 行（签名 36 + 函数体 1374）。第四步与前三步不同，属于**语义有风险的重点改动**，需满足两个前置条件才动手：
1. **实机数据支撑**：装包实测后 `compiler instruction limit` 警告仍在，才值得继续；若已消失则停止（避免无收益重构）。
2. **用户确认方案**：推荐用 `rememberXxxState()` 状态容器模式（与已完成的 `rememberPasswordListQuickFilterToggles` 同款），**不要再开 Host**——状态下沉后调用处仍需读写这些状态，再开 Host 会导致参数回调爆炸。分组顺序建议：对话框状态 → 选择状态 → 滚动状态 → 派生筛选结果（前两组机械安全，后两组涉及重组边界，需逐组装包回归）。

> 接力的 AI：第四步动手前务必确认上面两个前置条件均已满足。

---

## 四、验证

每步合入后装 debug 包：

```bash
# 清空旧日志
adb logcat -c

# 滚动密码列表、打开详情、做几个操作

# 看是否还有编译器警告
adb logcat -d -v time --pid=$(adb shell pidof com.bastion.app) | grep "compiler instruction limit"
```

- **还有** → 继续下一步
- **没了** → 完成，JIT 已生效

**不要用本地 `.class` 大小估收益**——ART 报的是 dex 指令数，与 JVM 字节码不是同一度量，靠装包验证才是权威。

---

## 五、已踩的坑（务必避开）

### 5.1 `@Stable` 不能用于 class

```kotlin
@Stable private class Xxx   // ❌ 编译失败：annotation is not applicable to target 'class'
private class Xxx           // ✅ 普通即可
```

`@Stable` 的适用目标是 function / type usage / type parameter / getter，**不适用于 class**。

### 5.2 新代码插进注解与函数名之间

**错误锚点**：`private fun PasswordListMainPaneHost(`

```kotlin
// 原文件
@Composable
private fun PasswordListMainPaneHost(...)

// 用上面那个锚点插入新代码 → 结果：
@Composable
// 新插入的 class / 函数（一堆）
private fun PasswordListMainPaneHost(...)   // ← @Composable 被"抢走"，修饰了新插入的 class
```

**正确做法**：锚点选注解**之前**的独立位置（如前一个函数的结束 `}` + 空行），或用 IDE 的"在此处插入"功能。

### 5.3 批量正则改名的三类误伤

用 sed / PowerShell `-replace` 批量改变量名（如 `quickFilterFavorite` → `quickFilterToggles.favorite`）会误伤：

1. **函数定义的参数名**：`quickFilterFavorite: Boolean,` → 被改成 `quickFilterToggles.favorite: Boolean,`（语法错误）
2. **函数调用的命名参数名**：`quickFilterFavorite = false,`（左侧）→ 被改成 `quickFilterToggles.favorite = false,`（"Only expressions are allowed in this context"）
3. **同文件其他函数的同名局部变量**：如 `PasswordListMainPaneHost` 函数体内它自己的参数 `quickFilterFavorite`

**对策**：大范围改名**优先用 IDE 的 rename symbol**（语义级，不误伤）。如果只能用正则，替换后必须**靠编译迭代修复**，且修复要按"函数定义 → 命名参数 → 函数体引用"分类逐一处理。本次第一步靠编译迭代了 4 轮才干净。

### 5.4 命名参数 vs 赋值

Kotlin 的命名参数 `xxx = value` 与赋值 `xxx = value` 在文本上完全一样，但语义不同。批量替换无法区分，必须靠编译错误定位修复。

---

## 六、禁止动作

- ❌ 不要改 `PasswordListTopSection` / `PasswordListMainPaneHost` 等子组件的**参数签名**——它们被多处调用，改动会牵连太广。
- ❌ 不要把 `rememberSaveable` 改成 `remember`——会丢失旋屏恢复。
- ❌ 不要在没有装包验证的情况下"猜"收益——靠 logcat 的 ART 警告判定。
- ❌ 不要一次拆太多块——每步合入后装包验证，确认行为无回归再继续。

---

## 七、参考提交

| 提交 | 说明 |
|------|------|
| `50c6192` | 第一步：快捷筛选状态下沉（本计划的起点） |
| `bbdd921` | 日志风暴修复：`extractHost` / `parseWebsite` 改静默 `runCatching` |
| `77ecffe` | 看门狗：息屏/低功耗下心跳延迟误判为主线程卡死 |
| `d9fa20e` | 看门狗：心跳链断裂导致 `blockedForMs` 无限累加 |
| `feec0cf` | 滑动删除改长按激活（方案 A） |
| `1fd8490` | WiFi/SSH/笔记/证件 编辑页移动语义对齐 |
| `843a73b` | 恢复验证器/通行密钥/卡包页的快捷筛选入口 |

---

## 八、执行进度

| 步骤 | 提交 | 状态 | 说明 |
|------|------|------|------|
| 0 | `50c6192` | ✅ 已完成 | 快捷筛选状态下沉 |
| 1 | `a7702b7` | ✅ 已完成（CI #33908818507 绿） | `configuredQuickFilterItems` + 重置 effect 下沉为 `rememberPasswordListConfiguredQuickFilterItems` |
| 2 | `530a674` + `38bc726` | ✅ 已完成（CI #33934428304 绿） | 两个嵌套 Composable 提升为顶层 `PasswordListTopSectionHost` / `PasswordListMainPaneSection`；quickFilter 32 参数收拢为 `quickFilterToggles` 容器 |
| 3 | `95954e3` + MoveSheet 提交 | ✅ 已完成（CI 绿） | 三个大块调用全部提升为顶层 Host：`PasswordListQuickStatusDialogsHost`（10 参数）/ `PasswordListDialogsHost`（31 参数）/ `PasswordBatchMoveSheetHost`（19 参数，17 同名转发 + 2 赋值 lambda，无计算型参数，指令数收益小但清单闭环）；主函数余 1410 行 |
| 验证 | — | ⏳ 待用户装包 | logcat 无 `compiler instruction limit`（权威判定） |

> 接力的 AI：完成一步后请更新本表 + 在 [二、现状](#二现状截至-50c6192) 补充新提交的函数范围。
>
> **步骤 2 实施备注（供后续参考）**：
> - host 函数参数类型直接从子组件签名复制（零推断风险）；调用处除「修改外部 var 的 setter lambda」外全部 `xxx = xxx` 同名转发；
> - 修改外部 var 的 13 个 setter lambda 仍由主函数创建后传入（捕获语义不变）；
> - 踩坑记录：提升为顶层后参数需要显式类型名，`BitwardenViewModel`、`CoroutineScope` 此前从未被 import（嵌套函数靠类型推断），需补齐（`38bc726`）。
>
> **步骤 3 实施备注（供后续参考）**：
> - 4 个赋值 lambda（QS）+ 12 个赋值 lambda（DLG，含超大的 `onDeleteSelection` 整体转发）仍由主函数创建，host 内 `xxx = xxx` 简单转发，捕获语义不变；
> - `selectedCount` 不再作为 host 参数，host 内直接 `selectedItemKeys.size` 计算；
> - 踩坑记录：`PasswordBatchTransferGlobalProgressState` / `PasswordBatchDeleteGlobalProgressState` 定义在 `com.bastion.app.ui.password` 包，而 PasswordListContent.kt 的 package 是 `com.bastion.app.ui`——**同名目录≠同包**，参数类型需写全限定名（参照 MainPaneSection 写法）；`ManualStackDialogMode` / `QuickStatusKeePassSyncState` 虽然文件在 `ui/password/` 目录，但 package 声明是 `com.bastion.app.ui`，同包短名可直接用；
> - 校验手段：内容锚定 + 括号配平定位调用块（行号漂移免疫），插入后全文件 `()` `{}` 配平 0/0、host 调用/定义各 1、全部参数类型按「import ∪ 同包 ∪ 内建」规则判定可解析。
