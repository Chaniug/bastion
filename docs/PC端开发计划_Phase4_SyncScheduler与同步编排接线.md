# PC 端 Phase 4 收尾：SyncScheduler 与同步编排接线

> 分支策略：dev 开发 → Desktop-Build CI 绿 → 合 main。桌面端物理隔离于 `desktop/`，不碰 `Bastion/` 安卓模块。
> 状态：📝 **代码已落地，等待本地编译 + GitHub Desktop-Build CI 验证**（本环境 Bash 工具异常，无法本地跑 gradle；提交走 dev 由 CI 验证）。

## 背景与缺口

PC 端 Phase 0~3 已完成（骨架 / Bitwarden 核心 / KDBX 核心 / SQLDelight 持久化），Phase 5 三 Tab UI 也已搭好。
但在复核时发现两处**功能性缺口**（计划 §2.5 要求、此前未实现）：

1. **`SyncScheduler` 完全缺失** —— 计划 §2.5 明确要求「WorkManager → 协程定时任务」的桌面替代：周期同步 + 手动 `requestSync()` 事件驱动。此前没有任何后台周期同步。
2. **`BitwardenSyncOrchestrator` 未被装配** —— `shared/.../bitwarden/sync/BitwardenSyncOrchestrator.kt` 是一套成熟的同步编排器（去重 / 节流 / 重试退避），
   但全仓**零处实例化/调用**（除自身定义）。UI 直接调 `repository.sync()`，而设置页的「自动同步」开关只是写入存储、**无人消费**。
   → 结果：自动同步开关是死的，无任何后台同步行为。

## 目标

- 新增 `SyncScheduler`：应用启动即进入周期循环；自动同步开启时对所有「已解锁」vault 周期触发 `PERIODIC` 同步；启动时额外触发一次 `APP_RESUME` 即时同步；退出时取消。
- 把已有的 `BitwardenSyncOrchestrator` 接入 `AppContainer`，通过 lambda 委托给 `BitwardenRepository`，使「自动同步」开关与网络门控真正生效。
- 桌面端无移动网络概念，`checkNetwork` 直接返回 `NetworkGateResult.ALLOWED`（见计划 §3.1）。

## 改动文件

### 新增 `desktop/desktop/src/main/kotlin/com/bastion/desktop/platform/SyncScheduler.kt`
- `class SyncScheduler(orchestrator, repository, intervalMs = 15min)`
- `start()`：启动 coroutine 循环；先 `APP_RESUME` 即时同步，然后每 `intervalMs` 在自动同步开启时对所有已解锁 vault `requestSync(PERIODIC)`。
- `requestSyncNow(vaultId)`：手动入口（`MANUAL`，供 UI 统一入口保留）。
- `stop()`：取消循环作用域。
- 内部 `triggerForUnlockedVaults` 用 `runCatching` 包住 `getAllVaults()`，避免枚举异常拖垮循环。

### 改 `desktop/desktop/src/main/kotlin/com/bastion/desktop/di/AppContainer.kt`
- 新增 `appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`（进程级，供编排器派生请求协程）。
- 新增 `syncOrchestrator: BitwardenSyncOrchestrator`（lazy），lambda 接线：
  - `isAutoSyncEnabled = { bitwardenRepository.isAutoSyncEnabled }`
  - `checkNetwork = { NetworkGateResult.ALLOWED }`
  - `isVaultUnlocked = { id -> bitwardenRepository.isVaultUnlocked(id) }`
  - `executeSync = { id, _ -> 把 BitwardenRepository.SyncResult 映射为 SyncExecutionOutcome }`
    - `Success` → `SyncExecutionOutcome.Success(...)`
    - `Error` → `RetryableError(message)`（让其按退避重试）
    - `EmptyVaultBlocked` → `Blocked(SyncBlockReason.AUTH_REQUIRED, reason)`（防误覆盖，不重试）
- 新增 `syncScheduler: SyncScheduler`（lazy）。

### 改 `desktop/desktop/src/main/kotlin/com/bastion/desktop/Main.kt`
- `main()` 启动窗口前 `AppContainer.syncScheduler.start()`。
- `Window` 的 `onCloseRequest` 改为先 `syncScheduler.stop()` 再 `exitApplication()`。

> UI 侧「立即同步」按钮保持直接调 `repository.sync()`（已工作、可立即看到结果），与后台周期同步互不冲突——
> `BitwardenRepository.sync` 内部按 vault 加互斥锁，手动与周期同步不会并发同一 vault。

## 设计要点 / 决策

- **编排器只负责「何时发起」，去重/节流/重试由 `BitwardenSyncOrchestrator` 负责**（其 `requestSync` 内部已处理 `PERIODIC` 节流、`RETRY` 退避、`MANUAL` 不节流）。
- **锁仓即停**：周期同步前过滤 `isVaultUnlocked`；应用重启后 vault 为锁态（会话密钥清空），周期同步自动被编排器 `VAULT_LOCKED` 拦截，需用户解锁后才恢复——符合安全预期。
- **自动同步开关现在真正生效**：编排器在 `isAutoSyncEnabled()` 为 false 时 `AUTO_SYNC_DISABLED` 阻断（仅影响自动/周期，不影响 UI 手动按钮）。

## 验证清单

- [ ] 本地 `:shared:compileKotlinJvm :desktop:compileKotlin` BUILD SUCCESSFUL（本环境 Bash 故障，待恢复或 CI 验证）
- [ ] dev 推送后 **Desktop-Build** CI 绿（含 `:desktop:packageExe`）
- [ ] 运行时：登录并解锁 → 设置开启自动同步 → 等待 15min 或改 `intervalMs` 验证后台周期同步；锁仓后周期同步应停止
- [ ] 设置关闭自动同步 → 周期循环应跳过（日志 `Auto sync disabled`）

## 不在本步范围（后续）

- Phase 6：端到端联调与冲突验证，需要 Windows 真机 + Azure 桌面应用注册（client_id / 回环端口 52525）+ OneDrive 账号。CI 只编译+打包，不跑 GUI 测试。
- 可选增强：把 UI「立即同步」按钮也改走 `syncScheduler.requestSyncNow(vaultId)` 以统一同步路径（当前为低风险保留原行为）。
