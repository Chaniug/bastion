# Bastion Bitwarden 同步卡死修复 — 阶段1 实现交接（2026-08-27）

> 设备/构建：HONOR BKQ-AN00，Android API 37
> 关联日志：`bastion_logs_20260827_101839.txt`
> 关联计划：`/workspace/bastion_bitwarden_sync_401_diagnosis_plan.md`

## 一、新日志推翻了"纯 token 过期"假设

`Bitwarden Persisted Logs` 段关键证据：

- `two_factor` 登录成功（行 1940）→ `sync START forced=true`（1941）→ `fullSync DONE 5249ms, 219 条`（1943）→ `overallSyncResult SUCCESS`（1944）。重登后 token 全新、网络路径能跑通。
- 仅 2~5 秒后第二次 `sync START forced=true`（1945）**之后全无**：无 `accessToken expiring`、无 `fullSync START`、无 `EXCEPTION`。
- 即 sync2 卡在 `sync()` 内 **sync START(827) → fullSync START(982)** 之间（上传/待处理/预检阶段），且 token 为全新值 → **排除 401/过期**。

结论：首次全量同步后，连接池复用了被反代（bit.valk.ccwu.cc 前的 nginx/Cloudflare）静默关闭的空闲连接，后续请求挂到原 `readTimeout=60s` 才失败 → 用户感知"很慢很慢 / 感觉没同步"。

## 二、阶段1 改动（commit 待合 dev 后确认 CI）

### 1. OkHttp 401 自动恢复拦截器 — `BitwardenApiFactory.kt`
- 新增 `interface BitwardenTokenRefresher { fun refreshForHost(host): String? }` 与私有 `Authenticator`：任意 Bitwarden 请求返回 401 → 回调按 host 刷新 token → 用新 token 重试一次（OkHttp 对同一响应链只调一次，`priorResponse!=null` 即停，防死循环）。
- `BitwardenRepository` 在 `getInstance` 中注册自身为实现（`refreshForHost` 内部 `runBlocking(Dispatchers.IO)` 调 `refreshTokenByHost`），解决"只依赖 accessTokenExpiresAt 预判刷新"的盲区。覆盖同步/附件/Send 等所有 Bitwarden 调用。

### 2. 网络超时与连接池收紧 — `BitwardenApiFactory.kt`
- `readTimeout`/`writeTimeout` 60s → **30s**，避免死连接挂死几十秒。
- `retryOnConnectionFailure(true)`（显式，RST 关闭的连接立即重试新连接）。
- 连接池 `keepAliveDuration` 5min → **2min**，缩短空闲连接存活，降低复用已被反代关闭连接的概率。
- `pingInterval(30s)` 保留（HTTP/2 keepalive 快速发现死连接）。

### 3. 主 Vault 界面消费同步错误 — `SimpleMainScreen.kt`
- 原 `LaunchedEffect` 只处理 `SyncFinished`，未处理 `ShowError` → 主 Vault 界面静默失败。
- 改为 `when`：收到 `ShowError` 时用 `Toast(LENGTH_LONG)` 明确提示，终结"感觉没同步"。

### 4. 上传阶段细粒度诊断 — `BitwardenRepository.kt` / `BitwardenSyncService.kt`
- `sync()` 内 `processPendingOperations` / `uploadLocalEntries` / `uploadModifiedEntries` 各加 START/DONE 计时日志（含 uploaded/failed/processed）。
- `uploadLocalEntries` 内 `uploadPendingSecureItems` / `uploadPendingPasskeys` 子调用加 START/DONE 计时日志。
- 目的：下一轮真机复现可精确定位到底卡在 877/902/913 哪一步（当前日志因该段无埋点无法定位）。

## 三、验证与待办

- [ ] dev 分支 Android CI 通过（GitHub Actions）。
- [ ] 真机（荣耀 Android 17）装预览版复现：已登录连续点 2 次同步，导出日志看 `Bitwarden Persisted Logs` 是否仍卡在上传阶段、具体哪一步，以及是否出现 `refreshTokenByHost` / `uploadPendingSecureItems` 等新增埋点。
- [ ] 若仍卡：依据新增埋点定位具体挂死的网络调用，针对性处理（如该调用缺独立超时/重试）。
- [ ] 阶段1 稳定后，将 dev 合并到 main（用户确认后）。

## 四、备注

- `BitwardenDiagLogger.append()` 为**单线程异步落盘**（`writeExecutor.execute`），导出日志为异步快照；分析"某步之后无日志"时，结论应为"代码未执行到该步"而非"日志滞后"——本例 sync2 确未到达 `fullSync START`。
- 401 Authenticator 对多 vault 同 host 场景仅刷新首个匹配 vault；单 vault 自托管场景无影响。
