# Bitwarden 同步优化：revision-date 轻量预检跳过（P0）+ 60s 防抖（P1）

> 日期：2026-08-26 ｜ 分支：dev ｜ 来源：借鉴 Bitwarden 官方 Android `VaultSyncManagerImpl` 与 Keyguard `SyncByBitwardenTokenV2Impl` 的同步设计。

## 背景与根因

- bastion 早就有 `GET /sync?sinceRevisionDate`（增量尝试），但 **Vaultwarden 等自建服务器忽略 `sinceRevisionDate`**，每次都返回整个保险库 → 每次同步都被迫全量下载 + 解密，明显偏慢。
- 官方与 Keyguard 的共识做法：**不在服务器端减量，而是在客户端先用一次极小的 `GET /accounts/revision-date`（只返回一个时间戳字符串）判断是否要全量**。一致就整段跳过下载+解密。

## 改动清单

| 文件 | 改动 |
|------|------|
| `bitwarden/api/BitwardenApi.kt` | `BitwardenVaultApi` 新增 `getRevisionDate()`：`@GET("accounts/revision-date")`，返回 `Response<ResponseBody>`（绕过 JSON 转换器，手动读字符串）。 |
| `bitwarden/repository/BitwardenRepository.kt` | 新增 `shouldSkipFullSyncPull(...)`：先 60s per-vault 防抖（用 `vault.lastSyncAt`），再打 `getRevisionDate` 与本地 `vault.revisionDate` 比较；一致则跳过整库 pull。在 `sync()` 的 pull 之前插入跳过分支。`sync()` 新增 `forced` 参数。 |
| `bitwarden/sync/BitwardenRepositorySync.kt` | `syncViaCoordinator` 新增 `forced` 参数并透传到执行体 lambda `sync(vaultId, pullAfterPush, forced)`；`syncForUserVisibleRequest` 设 `forced=true`。 |
| `bitwarden/viewmodel/BitwardenViewModel.kt` | `runRepositorySyncThroughCoordinator` 透传 `forced`；`runSync` 中 `forced = reason == MANUAL \|\| reason == RETRY`；`refreshSendsViaCoordinator` 设 `forced=true`。 |
| `bitwarden/sync/BitwardenSyncWorker.kt` | 后台恢复型同步 `WORKER_RECOVERY` 设 `forced=true`。 |
| `bitwarden/.../BitwardenRepositorySyncTest.kt` | 更新 guard 断言字符串以匹配新 lambda `sync(vaultId, pullAfterPush, forced)`。 |

## 跳过判定（全部满足才跳过）

1. `forced == false`（用户手动 / 重试 / 恢复一律全量）
2. 本地无待上传改动（`pendingOpDao.getRunnableOperationsByVault(...).isNotEmpty()` 或本次已上传）
3. 距上次成功全量同步 < 60s（per-vault 防抖，避免秒级抖动）
4. 远程 `revision-date` 与本地 `vault.revisionDate` 一致

## 安全性

- 误跳（假阴性）只会"多拉一次全量"，绝不丢数据。
- 取不到 revision-date / 网络异常 → `runCatching` 兜底走全量。
- 首装或 `vault.revisionDate == null` → 不跳过，全量拉取。
- 本地有未上传改动 → 不跳过（先推送再拉他人变更）。

## 验证

- CI：dev 分支 Android CI（lint + `:app:assembleDebug` + 单元测试）须全绿；CodeQL 同步扫描。
- 真机（荣耀 Android 17）：登录 Vaultwarden → 首次全量；再次触发同步日志应出现 `revisionDate precheck SKIP` 且耗时显著下降；任一端改一条条目使 revision-date 变化，下次自动同步应全量。
- 手动下拉刷新永远是全量（forced）。

## 注意

- 仅改了 `Bastion/app`（Android）模块；`desktop/shared` 为独立模块，未同步改动。
- 若后续在桌面端也想要此优化，需在 `desktop/shared` 对应副本镜像同样改动。
