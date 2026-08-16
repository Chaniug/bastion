# Bastion Plus 全链路移除记录（2026-08-16）

## 背景与决策

用户反馈：快速初始化设置（`QuickSetupScreen`）中的 PLUS 功能区（`MONICA_PLUS` 步骤）
早已移除，但向导里仍残留无意义的"Bastion Plus 已开启"纯文本页，需要清理。

经勘察确认 PLUS 早已从付费转为"免费已激活"展示页：
- 设置页购买入口已下线（`onNavigateToBastionPlus` 无 UI 调用）
- `BastionPlusScreen` 退化为"已激活（全部免费）"展示页（文案还是"求各位股东入资…要饿死了😭"）
- `isPlusActivated` 恒为 `true`（无任何 UI 设置入口），附件配额实际从不限制
- `PaymentScreen` 残留但无入口

**用户决策**：移除向导 PLUS 步骤 + 顺带清理 PLUS 全链路（含字段/门控，用户可见行为不变）。

## 改动清单（commit `cbc44731` + 修复 `dd0ea44b`）

### A. 快速初始化向导
- `QuickSetupScreen.kt`：删 `MONICA_PLUS` 枚举、when 分支、`BastionPlusStep` 组件、
  `onOpenBastionPlus` 参数；最后一步 `AUTHENTICATOR_CARD` 按钮文案改显示"完成"（`qs_finish`）。
  底部 `onNext` 基于 `steps.lastIndex` 动态判断，无需改动。

### B. PLUS UI 死代码
- 删 5 个文件：`BastionPlusScreen.kt`、`PaymentScreen.kt`、`data/PlusFeature.kt`、
  `ui/components/PlusFeatureCard.kt`、`ui/components/BastionPlusCard.kt`
- `Screens.kt`：删 `Screen.BastionPlus`、`Screen.Payment` 路由
- `MainActivity.kt`：删 import、4 处导航传参（SettingsScreen×2 / QuickSetup / Extensions）、
  BastionPlus + Payment 两个 composable 路由注册
- 传参链（SimpleMainScreen → SettingsTabContent / CompactDraggableTabContent → SettingsScreen）
  逐层删除 `onNavigateToBastionPlus`
- `ExtensionsScreen.kt` / `SettingsScreen.kt`：删 `onNavigateToBastionPlus` 参数
- `SyncBackupScreen.kt`：删 `SyncBackupItem` 的 `badge` 参数 + Plus 徽章 UI（badge 恒为 null）

### C. 字段 / 门控简化（用户可见行为不变）
- `AppSettings.kt`：删 `isPlusActivated = true` 字段
- `SettingsManager.kt`：删 `IS_PLUS_ACTIVATED_KEY`、读（`preferences[...] ?: true`）、
  写（`updatePlusActivated` 函数）
- `SettingsViewModel.kt`：删 `updatePlusActivated` 委托
- 附件链路：删 `UploadRequest.isPlusActivated`、`AttachmentQuotaPolicy.check(...)` 调用、
  删 `AttachmentQuotaPolicy.kt` 文件（其 `check` 恒返回 null，删除后附件行为不变——本就不限）、
  `AttachmentsEditSection` / `flushPendingDraftsTo` / `AddEditPasswordScreen` 删传参
- 主题注释：`ThemeAndColorSchemeScreen` / `Theme.kt` / `Color.kt` 删 "Catppuccin (Plus)" 注释
  （Catppuccin 主题本就无门控，只删注释）

### D. 资源
- `values/strings.xml` + `values-zh/strings.xml`：删 38 条 `plus_*` / `bastion_plus_*` /
  `qs_plus_*` / `qs_step_bastion_*` / `sync_backup_plus_hint` / `payment_paid_button` /
  `attachment_upgrade_plus_action` 等字符串

## 保留项（有意）

- `AttachmentError.QuotaExceeded` 类型与 `attachment_error_quota_exceeded` 文案：
  仍被 `AttachmentsDetailSection` / `AttachmentsEditSection` 的 when 分支引用（错误兜底）。
  若未来重新引入附件数量限制，可复用。
- `PlusBlurRemovalTest`（`app/src/test/.../plus/`）：PLUS 模糊效果**防回归测试**，
  检查生产代码不含 `plusBlur` / `BastionPlusBlur` 等 token，与本次清理无关，保留。

## 过程中的坑（重要）

- 删 `BastionPlusStep` 时，其前一行孤立的 `@Composable` 注解残留，错误贴到
  `togglePasswordCardField` 上 → 该纯函数被当作 @Composable，在 `onCheckedChange`
  lambda（非 composable 上下文）调用时报
  `@composable invocations can only happen from the context of a @composable function`
  （QuickSetupScreen.kt:910/918）。**教训：删除 @Composable 函数时，注解行必须一并删除。**
- 编译错误日志拿不到时，可用 GitHub Actions 页面的 **Annotations**（WebFetch 提取
  `build_gate` 的 notice）定位错误行。

## 验证

- dev CI：`#422` 首次编译失败（孤立注解）→ `#423` 修复后 **success**
- 用户可见变化：快速初始化向导少一步（Bastion Plus 已开启页）；附件行为不变（本就不限）
- 真机验收路径（荣耀安卓17）：首次启动初始化向导应为 9 步（不再有 Bastion Plus 步骤）；
  密码详情页附件功能正常（无配额提示）
