# Bastion Phase B.3：PasswordViewModel 拆分执行计划

> **文档目的**：在 B.1/B.2.1/B.2.2/B.2.3 完成后，执行 B.3 —— 把 4162 行的 `PasswordViewModel.kt`
> 按职责簇拆分为多个协调器/工具类。**本文档即约定 #5 要求的"重点改动计划"，确认后逐步实施。**
>
> **创建时间**：2026-08-02
> **状态**：🟡 执行中（集群 1 ✅ 完成，CI 绿 `30725125903`+`30725511081`；集群 2（Bitwarden 离线缓存）准备中）
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
| 2 | Bitwarden 离线缓存 | — | — | — | ⬜ |
| 3 | KeePass 同步协调器 | — | — | — | ⬜ |
| 4 | 类别过滤状态 | — | — | — | ⬜ |
| 5 | 跨存储迁移 | — | — | — | ⬜ |
| 6 | 删除/归档编排 | — | — | — | ⬜ |
| 7 | 主密码/历史 | — | — | — | ⬜ |
| 8 | 构造注入 | — | — | — | ⬜ |
