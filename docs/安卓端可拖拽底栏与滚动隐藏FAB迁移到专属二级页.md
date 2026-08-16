# 把"可拖拽底栏"和"滚动隐藏FAB"迁到专属二级页（含 CI #415 编译失败修复记录）

## 背景

用户反馈：两个开关——`useDraggableBottomNav`（可拖拽底部导航栏）、`hideFabOnScroll`（滚动时隐藏悬浮按钮）——"毕业"后放在实验弹窗里显得突兀、与同组其他开关风格不搭，要求迁入「界面与布局」下属的专属二级页，并做 UI 风格对齐。

## 问题：CI #415 失败根因

- 之前基于 **main 线**（commit `8e07f7e5`/`5bc5ff80`）做的迁移，经 Git Data API 推到 dev 时生成了**孤儿提交 `c27eb991`**（父指向 `010127ab`，与 dev 线脱节，且当前已不在 `origin/dev` 历史中）。
- `c27eb991` **错误地把实验弹窗里的「KeePass DX 类引擎」开关退化改回「减少动画」**；而 `reduceAnimations` 在 dev 线（#414 "预览功能整合：移除减少动画、新增 KeePass DX 引擎"）**已不存在**，形成悬空引用。
- 后果：`main.yml`（Android CI debug）的 `Build Debug APK (build gate)` 步骤在 **约 2 分钟内编译失败**（run #415，`head_sha=c27eb991`，conclusion=failure；注意非超时，45min 超时未触及）。
- 当前 dev 线实验弹窗的真实组成是 **Bitwarden 底部状态栏 + KeePass DX 类引擎**（**没有**"减少动画"）。

> 关键教训：dev 与 main 线已经分叉——dev 线做过"移除减少动画、新增 KeePass DX 引擎"，main 线没有。任何针对 dev 的改动都要以 `origin/dev` 为基准，绝不能把已删除的 `reduceAnimations` 当作存在符号引用。

## 修复（基于 dev 当前头 `86090911` 重做）

- 新分支 `fix/dev-realign` 从 `origin/dev` (`86090911`) 切出，保证与 dev 线一致。
- `BottomNavSettingsScreen.kt`：在 `LazyColumn` 内、`bottom_nav_auto_hide_single_tab` 项之后新增 draggable 开关项（`BottomNavConfigRow`，`showDragHandle = false`，风格对齐已有的 `auto_hide_single_tab`），并新增 import `androidx.compose.material.icons.filled.SwipeUp`。
- `PageAdjustmentCustomizationScreen.kt`（其中定义 `PasswordListCustomizationScreen`）：新增本地 `previewHideFabOnScroll` 状态，并在页内开关列表末尾追加 `SwitchSettingsCard`（风格对齐页内既有的 5 个开关）。
- `SettingsScreen.kt`：删除**主体**「界面与布局」导航入口区块里已迁出的 `draggable` + `fab` 两个 `Row`，避免同一 `AppSettings` 字段出现两份入口（实验弹窗里不重复，仅主体此处冗余）。
- **实验弹窗的 Bitwarden + KeePass 保持不动**（绝不变回已删除的"减少动画"）。

## 不动的部分

- `AppSettings.kt` / `SettingsViewModel.kt` / `SettingsManager.kt`：字段（`useDraggableBottomNav` 默认 false、`hideFabOnScroll` 默认 true）与更新方法在最初添加预览开关时已就绪。
- `strings.xml` / `values-zh/strings.xml`：`draggable_bottom_nav*` 与 `hide_fab_on_scroll_*` 中英文案早已存在。
- `MainActivity.kt`：两个目标页都已持有 `viewModel`，无需改导航。

## 验证

- 推到 dev 后由 `main.yml` 重跑 CI，预期 `assembleDebug` 通过（不再引用 `reduceAnimations`）。
- 真机验收路径（荣耀安卓 17）：
  1. 设置 → 界面与布局 → 底部导航栏设置 → 末尾出现「可拖拽底部导航栏」+ SwipeUp 图标 + Switch（默认关）
  2. 设置 → 界面与布局 → 密码列表自定义 → 末尾出现「滚动时隐藏悬浮按钮」+ Switch（默认开）
  3. 设置 → 高级设置 → 实验功能 只剩 **Bitwarden 底部状态栏 + KeePass DX 类引擎** 2 个（无"减少动画"）

## 接力提示

- dev 与 main 线分叉：`reduceAnimations` / `keepassDxLikeMutationEnabled` 等符号在两条线状态不同，后续合并 main 进 dev 或反之时需人工核对这些符号，避免再次引入悬空引用。
- 本修复已让 dev 线的二次调整代码与"迁入专属页、UI 风格对齐"的语义一致；`#415` 的根因（错误提交 `c27eb991`）已通过本次基于 `86090911` 的干净重做规避。
